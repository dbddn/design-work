package com.music.reco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AssistantModelProperties(
        String apiKey,
        String baseUrl,
        String model,
        boolean thinkingEnabled,
        int timeoutSeconds,
        int daypartTimeoutSeconds
) {
}
