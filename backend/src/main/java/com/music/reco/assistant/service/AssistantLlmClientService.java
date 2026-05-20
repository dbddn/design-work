package com.music.reco.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.assistant.dto.AiCompletionResult;
import com.music.reco.assistant.dto.AiPromptMessage;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.config.AssistantModelProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssistantLlmClientService {
    private final AssistantModelProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public AssistantLlmClientService(AssistantModelProperties properties,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    public boolean isEnabled() {
        return properties.baseUrl() != null
                && !properties.baseUrl().isBlank()
                && properties.model() != null
                && !properties.model().isBlank();
    }

    public int daypartTimeoutSeconds() {
        return properties.daypartTimeoutSeconds();
    }

    public AiCompletionResult complete(List<AiPromptMessage> messages) {
        return complete(messages, properties.timeoutSeconds());
    }

    public AiCompletionResult complete(List<AiPromptMessage> messages, int timeoutSeconds) {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Ollama AI 能力尚未配置完成，请先设置 base-url 与 model");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", buildMessages(messages));
        body.put("stream", false);
        body.put("format", "json");
        body.put("options", Map.of(
                "temperature", 0.35,
                "num_predict", 1200
        ));

        String raw;
        try {
            WebClient.RequestBodySpec requestSpec = webClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON);
            if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                requestSpec.header("Authorization", "Bearer " + properties.apiKey());
            }
            raw = requestSpec
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(3, timeoutSeconds)));
        } catch (WebClientResponseException.TooManyRequests error) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Ollama 服务当前触发限流，请稍后重试");
        } catch (WebClientResponseException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Ollama 服务调用失败: " + error.getStatusCode());
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Ollama 服务调用异常: " + error.getMessage());
        }

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Ollama 服务返回为空");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            String messageContent = stripThinking(root.path("message").path("content").asText("").trim());
            if (messageContent.isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Ollama 服务未返回有效答案");
            }
            return new AiCompletionResult(messageContent, List.of());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析 Ollama 响应失败: " + error.getMessage());
        }
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
}
