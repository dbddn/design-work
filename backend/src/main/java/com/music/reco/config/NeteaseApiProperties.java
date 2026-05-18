package com.music.reco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.netease-api")
public record NeteaseApiProperties(
        boolean enabled,
        String baseUrl,
        int timeoutMs,
        String defaultLevel
) {
}
