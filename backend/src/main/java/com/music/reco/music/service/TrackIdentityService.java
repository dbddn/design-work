package com.music.reco.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.config.NeteaseApiProperties;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.TrackDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Optional;

@Service
public class TrackIdentityService {
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final NeteaseApiProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public TrackIdentityService(LegacyJdbcRepository legacyJdbcRepository,
                                NeteaseApiProperties properties,
                                ObjectMapper objectMapper) {
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public Long resolveLocalTrackId(Long trackId) {
        if (trackId == null) {
            return null;
        }
        Optional<TrackDto> localTrack = legacyJdbcRepository.findTrack(trackId);
        if (localTrack.isPresent()) {
            return trackId;
        }
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "track not found");
        }
        return resolveNeteaseTrack(trackId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "track not found"));
    }

    private Optional<Long> resolveNeteaseTrack(Long neteaseTrackId) {
        try {
            JsonNode detailRoot = getJson("/song/detail?ids=" + neteaseTrackId);
            JsonNode songs = detailRoot.path("songs");
            if (!songs.isArray() || songs.isEmpty()) {
                return Optional.empty();
            }

            JsonNode song = songs.get(0);
            String title = textOr(song.path("name"), "");
            if (title.isBlank()) {
                return Optional.empty();
            }

            String artist = resolveArtists(song.path("ar"));
            String album = textOr(song.path("al").path("name"), "");
            String artwork = textOr(song.path("al").path("picUrl"), "");
            Integer durationSec = song.path("dt").isNumber() ? Math.max(1, song.path("dt").asInt() / 1000) : null;
            Integer releaseYear = null;
            if (song.path("publishTime").isNumber()) {
                long publishTime = song.path("publishTime").asLong();
                if (publishTime > 0) {
                    releaseYear = Instant.ofEpochMilli(publishTime).atZone(ZoneId.systemDefault()).getYear();
                }
            }

            return Optional.ofNullable(legacyJdbcRepository.findOrCreateMusicFromToplist(
                    neteaseTrackId,
                    title,
                    artist,
                    album,
                    artwork,
                    durationSec,
                    releaseYear
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private JsonNode getJson(String path) throws Exception {
        String payload = webClient.get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(Math.max(800, properties.timeoutMs())));
        return objectMapper.readTree(payload == null ? "{}" : payload);
    }

    private String resolveArtists(JsonNode artists) {
        if (!artists.isArray() || artists.isEmpty()) {
            return "未知歌手";
        }
        ArrayList<String> names = new ArrayList<>();
        for (JsonNode artist : artists) {
            String name = textOr(artist.path("name"), "");
            if (!name.isBlank()) {
                names.add(name);
            }
            if (names.size() >= 3) {
                break;
            }
        }
        return names.isEmpty() ? "未知歌手" : String.join(" / ", names);
    }

    private String textOr(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
