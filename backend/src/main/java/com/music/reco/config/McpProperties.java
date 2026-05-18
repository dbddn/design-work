package com.music.reco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        String projectRoot,
        String serverCommand,
        String serverEntry,
        String helperScript
) {
}
