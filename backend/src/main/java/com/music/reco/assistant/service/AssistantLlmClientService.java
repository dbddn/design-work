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
import java.util.ArrayList;
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
        return properties.apiKey() != null
                && !properties.apiKey().isBlank()
                && properties.baseUrl() != null
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AI 能力尚未配置完成，请先设置模型与 API Key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("input", buildInput(messages));
        body.put("extra_body", Map.of("enable_thinking", properties.thinkingEnabled()));

        String raw;
        try {
            raw = webClient.post()
                    .uri("/responses")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(3, timeoutSeconds)));
        } catch (WebClientResponseException.TooManyRequests error) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AI 服务当前触发限流，请稍后重试");
        } catch (WebClientResponseException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务调用失败: " + error.getStatusCode());
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务调用异常: " + error.getMessage());
        }

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务返回为空");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode output = root.path("output");
            if (!output.isArray() || output.isEmpty()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务未返回有效 output");
            }

            List<String> reasoningSummary = new ArrayList<>();
            String messageContent = "";

            for (JsonNode item : output) {
                String type = item.path("type").asText("");
                if ("reasoning".equals(type)) {
                    JsonNode summary = item.path("summary");
                    if (summary.isArray()) {
                        for (JsonNode summaryItem : summary) {
                            String text = summaryItem.path("text").asText("").trim();
                            if (!text.isBlank()) {
                                reasoningSummary.add(text);
                            }
                        }
                    }
                    continue;
                }

                if ("message".equals(type)) {
                    JsonNode contentArray = item.path("content");
                    if (contentArray.isArray()) {
                        for (JsonNode contentItem : contentArray) {
                            String text = contentItem.path("text").asText("").trim();
                            if (!text.isBlank()) {
                                messageContent = text;
                                break;
                            }
                        }
                    }
                }
            }

            if (messageContent.isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 服务未返回有效答案");
            }
            return new AiCompletionResult(messageContent, reasoningSummary);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析 AI 响应失败: " + error.getMessage());
        }
    }

    private List<Map<String, Object>> buildInput(List<AiPromptMessage> messages) {
        return messages == null ? List.of() : messages.stream()
                .filter(message -> message != null && message.content() != null && !message.content().isBlank())
                .map(message -> Map.<String, Object>of(
                        "role", normalizeRole(message.role()),
                        "content", List.of(
                                Map.of(
                                        "type", "input_text",
                                        "text", message.content().trim()
                                )
                        )
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
}
