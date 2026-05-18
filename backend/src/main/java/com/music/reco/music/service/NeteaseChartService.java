package com.music.reco.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.config.NeteaseApiProperties;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.NeteaseToplistDto;
import com.music.reco.music.dto.NeteaseToplistTrackDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class NeteaseChartService {
    private final NeteaseApiProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final LegacyJdbcRepository legacyJdbcRepository;

    public NeteaseChartService(NeteaseApiProperties properties,
                               ObjectMapper objectMapper,
                               LegacyJdbcRepository legacyJdbcRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public List<NeteaseToplistDto> listToplists(int limit, int trackLimit) {
        if (!properties.enabled()) {
            return List.of();
        }

        try {
            String payload = webClient.get()
                    .uri("/toplist/detail")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(Math.max(800, properties.timeoutMs())));
            JsonNode root = objectMapper.readTree(payload == null ? "{}" : payload);
            JsonNode list = root.path("list");
            if (!list.isArray() || list.isEmpty()) {
                return List.of();
            }

            List<NeteaseToplistDto> results = new ArrayList<>();
            int safeLimit = Math.max(1, limit);
            int safeTrackLimit = Math.max(1, trackLimit);
            for (JsonNode item : list) {
                if (results.size() >= safeLimit) {
                    break;
                }
                Long id = item.path("id").isNumber() ? item.path("id").asLong() : null;
                String name = textOr(item.path("name"), "未命名榜单");
                String cover = textOr(item.path("coverImgUrl"), "");
                String updateFrequency = textOr(item.path("updateFrequency"), "实时更新");

                List<NeteaseToplistTrackDto> topTracks = fetchToplistTracks(id, safeTrackLimit);

                results.add(new NeteaseToplistDto(id, name, cover, updateFrequency, topTracks));
            }
            return results;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<NeteaseToplistTrackDto> fetchToplistTracks(Long toplistId, int limit) {
        if (toplistId == null) {
            return List.of();
        }
        try {
            String payload = webClient.get()
                    .uri("/top/list?id=" + toplistId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(Math.max(1000, properties.timeoutMs())));
            JsonNode root = objectMapper.readTree(payload == null ? "{}" : payload);
            JsonNode tracks = root.path("playlist").path("tracks");
            if (!tracks.isArray() || tracks.isEmpty()) {
                return List.of();
            }

            List<NeteaseToplistTrackDto> result = new ArrayList<>();
            for (int i = 0; i < tracks.size() && result.size() < Math.max(1, limit); i++) {
                JsonNode track = tracks.get(i);
                Long neteaseTrackId = track.path("id").isNumber() ? track.path("id").asLong() : null;
                String title = textOr(track.path("name"), "未知歌曲");
                String artist = resolveArtists(track.path("ar"));
                String album = textOr(track.path("al").path("name"), "");
                String artwork = textOr(track.path("al").path("picUrl"), "");
                Integer durationSec = track.path("dt").isNumber() ? Math.max(1, track.path("dt").asInt() / 1000) : null;
                Integer releaseYear = null;
                if (track.path("publishTime").isNumber()) {
                    long publish = track.path("publishTime").asLong();
                    if (publish > 0) {
                        releaseYear = java.time.Instant.ofEpochMilli(publish)
                                .atZone(java.time.ZoneId.systemDefault())
                                .getYear();
                    }
                }

                Long localId = null;
                try {
                    localId = legacyJdbcRepository.findOrCreateMusicFromToplist(
                            neteaseTrackId,
                            title,
                            artist,
                            album,
                            artwork,
                            durationSec,
                            releaseYear
                    );
                } catch (Exception ignored) {
                    // Keep chart rows available even if local DB sync fails.
                    localId = null;
                }

                result.add(new NeteaseToplistTrackDto(
                        localId,
                        neteaseTrackId,
                        title,
                        artist,
                        album,
                        artwork
                ));
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String resolveArtists(JsonNode artists) {
        if (!artists.isArray() || artists.isEmpty()) {
            return "未知歌手";
        }
        List<String> names = new ArrayList<>();
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
        return value == null || value.isBlank() ? fallback : value;
    }
}
