package com.music.reco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.daypart-ai")
public record DaypartAiProperties(
        boolean enabled,
        String provider,
        String apiKey,
        String baseUrl,
        String model,
        int timeoutSeconds
) {
}
