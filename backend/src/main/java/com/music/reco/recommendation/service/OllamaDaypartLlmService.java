package com.music.reco.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.assistant.dto.AiCompletionResult;
import com.music.reco.assistant.dto.AiPromptMessage;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.config.DaypartAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class OllamaDaypartLlmService {
    private static final Logger log = LoggerFactory.getLogger(OllamaDaypartLlmService.class);

    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_SAFE_TIMEOUT_SECONDS = 45;

    private final DaypartAiProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public OllamaDaypartLlmService(DaypartAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled()
                && properties.baseUrl() != null
                && !properties.baseUrl().isBlank()
                && properties.model() != null
                && !properties.model().isBlank();
    }

    public AiCompletionResult complete(String requestId, String slotKey, List<AiPromptMessage> messages) {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "daypart AI is not configured");
        }

        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "messages", buildMessages(messages),
                "stream", false,
                "options", Map.of(
                        "temperature", 0.2,
                        "num_predict", 220,
                        "top_p", 0.85
                )
        );

        int timeoutSeconds = resolveTimeoutSeconds();

        String raw;
        try {
            log.info("daypart-ai request start requestId={} slotKey={} model={} timeoutSeconds={} messageCount={}",
                    safeValue(requestId), safeValue(slotKey), properties.model(), timeoutSeconds, messages == null ? 0 : messages.size());

            raw = webClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            log.info("daypart-ai request success requestId={} slotKey={} model={} responseChars={}",
                    safeValue(requestId), safeValue(slotKey), properties.model(), raw == null ? 0 : raw.length());
        } catch (WebClientResponseException error) {
            log.warn("daypart-ai request failed requestId={} slotKey={} status={} body={}",
                    safeValue(requestId), safeValue(slotKey), error.getStatusCode(), error.getResponseBodyAsString());
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "daypart AI call failed: " + error.getStatusCode()
            );
        } catch (Exception error) {
            log.warn("daypart-ai request exception requestId={} slotKey={} message={}",
                    safeValue(requestId), safeValue(slotKey), error.getMessage(), error);
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "daypart AI call failed: " + error.getMessage()
            );
        }

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart AI returned empty response");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            String content = root.path("message").path("content").asText("").trim();
            if (content.isBlank()) {
                log.warn("daypart-ai parse produced empty content requestId={} slotKey={} raw={}",
                        safeValue(requestId), safeValue(slotKey), raw);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart AI returned empty content");
            }
            return new AiCompletionResult(content, List.of());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            log.warn("daypart-ai parse failed requestId={} slotKey={} message={}",
                    safeValue(requestId), safeValue(slotKey), error.getMessage(), error);
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "failed to parse daypart AI response: " + error.getMessage()
            );
        }
    }

    private int resolveTimeoutSeconds() {
        int configured = properties.timeoutSeconds();
        if (configured <= 0) {
            return DEFAULT_SAFE_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_TIMEOUT_SECONDS, configured);
    }

    private List<Map<String, String>> buildMessages(List<AiPromptMessage> messages) {
        return messages == null ? List.of() : messages.stream()
                .filter(message -> message != null && message.content() != null && !message.content().isBlank())
                .map(message -> Map.of(
                        "role", normalizeRole(message.role()),
                        "content", message.content().trim()
                ))
                .toList();
    }

    private String normalizeRole(String role) {
        if ("assistant".equalsIgnoreCase(role)) {
            return "assistant";
        }
        if ("system".equalsIgnoreCase(role)) {
            return "system";
        }
        return "user";
    }

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
