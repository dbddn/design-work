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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DaypartLlmClientService {
    private static final Logger log = LoggerFactory.getLogger(DaypartLlmClientService.class);
    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_SAFE_TIMEOUT_SECONDS = 90;

    private final DaypartAiProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DaypartLlmClientService(DaypartAiProperties properties, ObjectMapper objectMapper) {
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
        return isSeedProvider() ? completeSeed(requestId, slotKey, messages) : completeOllama(requestId, slotKey, messages);
    }

    private AiCompletionResult completeSeed(String requestId, String slotKey, List<AiPromptMessage> messages) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "daypart Seed API key is not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", buildMessages(messages));
        body.put("temperature", 0.25);
        body.put("max_tokens", 900);
        body.put("response_format", Map.of("type", "json_object"));

        String raw = postJson(requestId, slotKey, "/chat/completions", body, true);
        try {
            JsonNode root = objectMapper.readTree(raw);
            String content = stripThinking(root.path("choices").path(0).path("message").path("content").asText("").trim());
            if (content.isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart Seed returned empty content");
            }
            return new AiCompletionResult(content, List.of());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "failed to parse daypart Seed response: " + error.getMessage());
        }
    }

    private AiCompletionResult completeOllama(String requestId, String slotKey, List<AiPromptMessage> messages) {
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "messages", buildMessages(messages),
                "stream", false,
                "format", "json",
                "options", Map.of(
                        "temperature", 0.2,
                        "num_predict", 900,
                        "top_p", 0.85
                )
        );

        String raw = postJson(requestId, slotKey, "/api/chat", body, false);
        try {
            JsonNode root = objectMapper.readTree(raw);
            String content = stripThinking(root.path("message").path("content").asText("").trim());
            if (content.isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart Ollama returned empty content");
            }
            return new AiCompletionResult(content, List.of());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "failed to parse daypart Ollama response: " + error.getMessage());
        }
    }

    private String postJson(String requestId, String slotKey, String uri, Map<String, Object> body, boolean authRequired) {
        int timeoutSeconds = resolveTimeoutSeconds();
        try {
            log.info("daypart-ai request start requestId={} slotKey={} provider={} model={} timeoutSeconds={} messageCount={}",
                    safeValue(requestId), safeValue(slotKey), providerName(), properties.model(), timeoutSeconds,
                    body.get("messages") instanceof List<?> list ? list.size() : 0);

            WebClient.RequestBodySpec requestSpec = webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON);
            if (authRequired || (properties.apiKey() != null && !properties.apiKey().isBlank())) {
                requestSpec.header("Authorization", "Bearer " + properties.apiKey());
            }

            String raw = requestSpec
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            log.info("daypart-ai request success requestId={} slotKey={} provider={} model={} responseChars={}",
                    safeValue(requestId), safeValue(slotKey), providerName(), properties.model(), raw == null ? 0 : raw.length());
            if (raw == null || raw.isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart AI returned empty response");
            }
            return raw;
        } catch (WebClientResponseException error) {
            log.warn("daypart-ai request failed requestId={} slotKey={} provider={} status={} body={}",
                    safeValue(requestId), safeValue(slotKey), providerName(), error.getStatusCode(), error.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart AI call failed: " + error.getStatusCode());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            log.warn("daypart-ai request exception requestId={} slotKey={} provider={} message={}",
                    safeValue(requestId), safeValue(slotKey), providerName(), error.getMessage(), error);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "daypart AI call failed: " + error.getMessage());
        }
    }

    private int resolveTimeoutSeconds() {
        int configured = properties.timeoutSeconds();
        if (configured <= 0) {
            return DEFAULT_SAFE_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_TIMEOUT_SECONDS, configured);
    }

    private List<Map<String, Object>> buildMessages(List<AiPromptMessage> messages) {
        return messages == null ? List.of() : messages.stream()
                .filter(message -> message != null && message.content() != null && !message.content().isBlank())
                .map(message -> Map.<String, Object>of(
                        "role", normalizeRole(message.role()),
                        "content", message.content().trim()
                ))
                .toList();
    }

    private boolean isSeedProvider() {
        String provider = providerName();
        return "seed".equals(provider) || "seed2".equals(provider) || "openai-compatible".equals(provider);
    }

    private String providerName() {
        return properties.provider() == null || properties.provider().isBlank()
                ? "ollama"
                : properties.provider().trim().toLowerCase(Locale.ROOT);
    }

    private String stripThinking(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.replaceAll("(?is)<think>.*?</think>", "").trim();
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
