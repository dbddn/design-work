package com.music.reco.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.config.NeteaseApiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NeteaseArtistImageService {
    private final NeteaseApiProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public NeteaseArtistImageService(NeteaseApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public String resolveAvatarUrl(String artistName, String existingAvatarUrl) {
        if (!isBlank(existingAvatarUrl) || isBlank(artistName) || !properties.enabled()) {
            return existingAvatarUrl;
        }
        return cache.computeIfAbsent(normalizeKey(artistName), key -> fetchAvatarSafely(artistName))
                .orElse(existingAvatarUrl);
    }

    private Optional<String> fetchAvatarSafely(String artistName) {
        try {
            JsonNode searchRoot = getJson("/cloudsearch?type=100&limit=1&keywords="
                    + UriUtils.encode(artistName.trim(), StandardCharsets.UTF_8));
            JsonNode artists = searchRoot.path("result").path("artists");
            if (!artists.isArray() || artists.isEmpty()) {
                return Optional.empty();
            }

            JsonNode artist = artists.get(0);
            String image = firstNonBlank(
                    artist.path("picUrl").asText(null),
                    artist.path("img1v1Url").asText(null),
                    artist.path("avatar").asText(null)
            );
            Long neteaseArtistId = artist.path("id").isNumber() ? artist.path("id").asLong() : null;
            if (!isBlank(image) || neteaseArtistId == null) {
                return Optional.ofNullable(blankToNull(image));
            }

            JsonNode detailRoot = getJson("/artist/detail?id=" + neteaseArtistId);
            JsonNode detailArtist = detailRoot.path("data").path("artist");
            return Optional.ofNullable(blankToNull(firstNonBlank(
                    detailArtist.path("cover").asText(null),
                    detailArtist.path("avatar").asText(null),
                    detailArtist.path("picUrl").asText(null)
            )));
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
                .block(Duration.ofMillis(Math.max(500, properties.timeoutMs())));
        return objectMapper.readTree(payload == null ? "{}" : payload);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
