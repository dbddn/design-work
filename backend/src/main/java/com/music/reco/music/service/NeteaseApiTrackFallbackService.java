package com.music.reco.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.config.NeteaseApiProperties;
import com.music.reco.music.dto.TrackDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class NeteaseApiTrackFallbackService {
    private static final Pattern LYRIC_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d+:)?\\d+(\\.\\d+)?\\]");

    private final NeteaseApiProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final Map<String, Optional<FallbackTrackData>> cache = new ConcurrentHashMap<>();

    public NeteaseApiTrackFallbackService(NeteaseApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public TrackDto enrichTrack(TrackDto track, boolean includeLyrics) {
        if (track == null || !properties.enabled()) {
            return track;
        }
        if (!includeLyrics && !isBlank(track.audioUrl()) && !isBlank(track.artworkUrl())) {
            return track;
        }

        String cacheKey = resolveCacheKey(track);
        Optional<FallbackTrackData> fallbackOptional = cache.computeIfAbsent(cacheKey, key -> fetchFallbackDataSafely(track));
        if (fallbackOptional.isEmpty()) {
            return track;
        }

        FallbackTrackData fallback = fallbackOptional.get();
        List<String> mergedLyrics = includeLyrics && (track.lyrics() == null || track.lyrics().isEmpty())
                ? fallback.lyrics()
                : track.lyrics();
        boolean usedFallback = (isBlank(track.audioUrl()) && !isBlank(fallback.audioUrl()))
                || (isBlank(track.artworkUrl()) && !isBlank(fallback.artworkUrl()))
                || (isBlank(track.description()) && !isBlank(fallback.description()))
                || (includeLyrics && (track.lyrics() == null || track.lyrics().isEmpty()) && !fallback.lyrics().isEmpty());

        return new TrackDto(
                track.id(),
                track.mcpTrackId(),
                track.title(),
                track.artist(),
                track.album(),
                track.genre(),
                blankOr(track.audioUrl(), fallback.audioUrl()),
                blankOr(track.artworkUrl(), fallback.artworkUrl()),
                track.durationSec(),
                blankOr(track.description(), fallback.description()),
                mergedLyrics == null ? List.of() : mergedLyrics,
                track.score(),
                usedFallback ? "NETEASE_API_FALLBACK" : track.source()
        );
    }

    public TrackDto refreshTrack(TrackDto track, boolean includeLyrics) {
        if (track == null || !properties.enabled()) {
            return track;
        }
        cache.remove(resolveCacheKey(track));
        return enrichTrack(track, includeLyrics);
    }

    private Optional<FallbackTrackData> fetchFallbackDataSafely(TrackDto track) {
        try {
            Long songId = resolveSongId(track);
            if (songId == null) {
                return Optional.empty();
            }

            JsonNode detailRoot = getJson("/song/detail?ids=" + songId);
            JsonNode songs = detailRoot.path("songs");
            if (!songs.isArray() || songs.isEmpty()) {
                return Optional.empty();
            }

            JsonNode song = songs.get(0);
            String artworkUrl = song.path("al").path("picUrl").asText(null);
            String title = song.path("name").asText(null);
            String album = song.path("al").path("name").asText(null);
            String artist = song.path("ar").isArray() && !song.path("ar").isEmpty()
                    ? song.path("ar").get(0).path("name").asText(null)
                    : null;

            JsonNode urlRoot = getJson("/song/url/v1?id=" + songId + "&level=" + properties.defaultLevel());
            JsonNode data = urlRoot.path("data");
            String audioUrl = data.isArray() && !data.isEmpty() ? data.get(0).path("url").asText(null) : null;

            JsonNode lyricRoot = getJson("/lyric?id=" + songId);
            String lyricText = lyricRoot.path("lrc").path("lyric").asText("");
            List<String> lyrics = lyricText == null || lyricText.isBlank()
                    ? List.of()
                    : lyricText.lines()
                    .map(String::trim)
                    .map(line -> LYRIC_TIMESTAMP_PATTERN.matcher(line).replaceAll("").trim())
                    .filter(line -> !line.isBlank())
                    .limit(16)
                    .collect(Collectors.toList());

            String description = buildDescription(title, artist, album);
            return Optional.of(new FallbackTrackData(audioUrl, artworkUrl, description, lyrics));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Long resolveSongId(TrackDto track) throws Exception {
        Long directId = parseSongId(track == null ? null : track.mcpTrackId());
        if (directId != null) {
            return directId;
        }

        String keyword = buildSearchKeyword(track);
        if (keyword.isBlank()) {
            return null;
        }

        JsonNode searchRoot = getJson("/cloudsearch?type=1&limit=10&keywords=" + UriUtils.encode(keyword, StandardCharsets.UTF_8));
        JsonNode songs = searchRoot.path("result").path("songs");
        if (!songs.isArray() || songs.isEmpty()) {
            return null;
        }
        JsonNode bestSong = chooseBestSong(track, songs);
        return bestSong != null && bestSong.path("id").isNumber() ? bestSong.path("id").asLong() : null;
    }

    private JsonNode chooseBestSong(TrackDto track, JsonNode songs) {
        String expectedTitle = normalizeForMatch(track == null ? null : track.title());
        String expectedArtist = normalizeForMatch(track == null ? null : track.artist());
        JsonNode bestSong = null;
        int bestScore = Integer.MIN_VALUE;

        for (JsonNode song : songs) {
            String candidateTitle = normalizeForMatch(song.path("name").asText(""));
            String candidateArtists = "";
            JsonNode artists = song.path("ar");
            if (artists.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode artist : artists) {
                    if (!builder.isEmpty()) {
                        builder.append(' ');
                    }
                    builder.append(artist.path("name").asText(""));
                }
                candidateArtists = normalizeForMatch(builder.toString());
            }

            int score = 0;
            if (!expectedTitle.isBlank() && candidateTitle.equals(expectedTitle)) {
                score += 100;
            } else if (!expectedTitle.isBlank() && (candidateTitle.contains(expectedTitle) || expectedTitle.contains(candidateTitle))) {
                score += 45;
            }
            if (!expectedArtist.isBlank() && candidateArtists.contains(expectedArtist)) {
                score += 60;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSong = song;
            }
        }

        int minimumScore = expectedArtist.isBlank() ? 80 : 120;
        return bestScore >= minimumScore ? bestSong : null;
    }

    private Long parseSongId(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String buildSearchKeyword(TrackDto track) {
        if (track == null) {
            return "";
        }
        String title = safePart(track.title());
        String artist = safePart(track.artist());
        if (!title.isBlank() && !artist.isBlank()) {
            return title + " " + artist;
        }
        if (!title.isBlank()) {
            return title;
        }
        return artist;
    }

    private String safePart(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[\\p{Punct}，。！？、：；“”‘’《》（）【】\\[\\]]", "");
    }

    private String resolveCacheKey(TrackDto track) {
        String mcpTrackId = track == null ? "" : safePart(track.mcpTrackId());
        if (!mcpTrackId.isBlank()) {
            return "mcp:" + mcpTrackId;
        }
        if (track != null && track.id() != null) {
            return "local:" + track.id();
        }
        return "search:" + buildSearchKeyword(track);
    }

    private JsonNode getJson(String path) throws Exception {
        String payload = webClient.get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(Math.max(500, properties.timeoutMs())));
        return objectMapper.readTree(payload == null ? "{}" : payload);
    }

    private String buildDescription(String title, String artist, String album) {
        String resolvedTitle = isBlank(title) ? "这首歌曲" : title.trim();
        String resolvedArtist = isBlank(artist) ? "未知歌手" : artist.trim();
        String resolvedAlbum = isBlank(album) ? "未知专辑" : album.trim();
        return "%s 来自 %s 的《%s》，当前由外部音乐接口补充播放与封面信息。"
                .formatted(resolvedTitle, resolvedArtist, resolvedAlbum);
    }

    private String blankOr(String primary, String fallback) {
        return isBlank(primary) ? fallback : primary;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record FallbackTrackData(
            String audioUrl,
            String artworkUrl,
            String description,
            List<String> lyrics
    ) {
    }
}
