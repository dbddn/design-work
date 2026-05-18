package com.music.reco.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.config.McpProperties;
import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.mcp.dto.McpLibrarySearchPayload;
import com.music.reco.mcp.dto.McpListeningHistoryArgs;
import com.music.reco.mcp.dto.McpListeningHistoryPayload;
import com.music.reco.mcp.dto.McpRecommendationPayload;
import com.music.reco.mcp.dto.McpSearchLibraryArgs;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class WebClientMcpClient implements McpClient {
    private static final Logger log = LoggerFactory.getLogger(WebClientMcpClient.class);
    private static final int MCP_BRIDGE_PORT = 9101;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebClientMcpClient(McpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public McpRecommendationPayload getRecommendations(McpHybridRecommendationArgs request) {
        try {
            return callTool("get_recommendations", request, McpRecommendationPayload.class);
        } catch (Exception error) {
            return fallbackRecommendations(request, error);
        }
    }

    @Override
    public McpRecommendationPayload getRecommendations(McpHybridRecommendationArgs request, int timeoutMs) {
        try {
            return callTool("get_recommendations", request, McpRecommendationPayload.class, timeoutMs);
        } catch (Exception error) {
            return fallbackRecommendations(request, error);
        }
    }

    @Override
    @Retry(name = "mcpService", fallbackMethod = "fallbackSearch")
    @CircuitBreaker(name = "mcpService", fallbackMethod = "fallbackSearch")
    public McpLibrarySearchPayload searchLibrary(McpSearchLibraryArgs request) {
        return callTool("search_library", request, McpLibrarySearchPayload.class);
    }

    @Override
    @Retry(name = "mcpService", fallbackMethod = "fallbackHistory")
    @CircuitBreaker(name = "mcpService", fallbackMethod = "fallbackHistory")
    public McpListeningHistoryPayload getListeningHistory(McpListeningHistoryArgs request) {
        return callTool("get_listening_history", request, McpListeningHistoryPayload.class);
    }

    @Override
    @Retry(name = "mcpService", fallbackMethod = "fallbackAnalyzeGenre")
    @CircuitBreaker(name = "mcpService", fallbackMethod = "fallbackAnalyzeGenre")
    public Map<String, Object> analyzeGenre(String genre) {
        return callTool("analyze_genre", Map.of("genre", genre), new TypeReference<>() {});
    }

    @Override
    @Retry(name = "mcpService", fallbackMethod = "fallbackFindSimilarGenres")
    @CircuitBreaker(name = "mcpService", fallbackMethod = "fallbackFindSimilarGenres")
    public Map<String, Object> findSimilarGenres(String genre, int maxResults) {
        return callTool("find_similar_genres", Map.of("genre", genre, "max_results", maxResults), new TypeReference<>() {});
    }

    @Override
    @Retry(name = "mcpService", fallbackMethod = "fallbackLibraryStats")
    @CircuitBreaker(name = "mcpService", fallbackMethod = "fallbackLibraryStats")
    public Map<String, Object> libraryStats() {
        return callTool("library_stats", Map.of(), new TypeReference<>() {});
    }

    private <T> T callTool(String toolName, Object payload, Class<T> targetType) {
        String stdout = executeHelper(toolName, payload);
        try {
            return objectMapper.readValue(stdout, targetType);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.MCP_SCHEMA_ERROR,
                    "failed to parse MCP response for tool " + toolName + ": " + error.getMessage());
        }
    }

    private <T> T callTool(String toolName, Object payload, Class<T> targetType, int timeoutMs) {
        String stdout = executeHelper(toolName, payload, timeoutMs);
        try {
            return objectMapper.readValue(stdout, targetType);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.MCP_SCHEMA_ERROR,
                    "failed to parse MCP response for tool " + toolName + ": " + error.getMessage());
        }
    }

    private <T> T callTool(String toolName, Object payload, TypeReference<T> targetType) {
        String stdout = executeHelper(toolName, payload);
        try {
            return objectMapper.readValue(stdout, targetType);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.MCP_SCHEMA_ERROR,
                    "failed to parse MCP response for tool " + toolName + ": " + error.getMessage());
        }
    }

    private String executeHelper(String toolName, Object payload) {
        return executeHelper(toolName, payload, properties.readTimeoutMs());
    }

    private String executeHelper(String toolName, Object payload, int timeoutMs) {
        try {
            return executeViaDaemon(toolName, payload, timeoutMs);
        } catch (Exception error) {
            log.warn("mcp daemon bridge failed tool={} cause={}, fallback to one-shot helper",
                    toolName, error.getMessage());
        }

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            log.info("mcp tool request tool={} payload={}", toolName, payloadJson);
            ProcessBuilder processBuilder = new ProcessBuilder(
                    properties.serverCommand(),
                    properties.helperScript(),
                    toolName,
                    "{}"
            );
            processBuilder.environment().put("MCP_PROJECT_ROOT", properties.projectRoot());
            processBuilder.environment().put("MCP_SERVER_COMMAND", properties.serverCommand());
            processBuilder.environment().put("MCP_SERVER_ENTRY", properties.serverEntry());
            processBuilder.environment().put(
                    "MCP_TOOL_ARGS_BASE64",
                    Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
            );
            processBuilder.redirectErrorStream(false);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(Math.max(2L, timeoutMs / 1000L + 2L), java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(ErrorCode.MCP_TIMEOUT, "MCP tool call timeout: " + toolName);
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            if (process.exitValue() != 0) {
                log.warn("mcp tool process failed tool={} exitCode={} stderr={}",
                        toolName, process.exitValue(), stderr);
                throw new BusinessException(
                        ErrorCode.MCP_5XX,
                        "MCP tool call failed: " + toolName + (stderr.isBlank() ? "" : " | " + stderr)
                );
            }

            if (stdout == null || stdout.isBlank()) {
                throw new BusinessException(ErrorCode.MCP_SCHEMA_ERROR, "empty response from MCP tool: " + toolName);
            }

            log.info("mcp tool response tool={} stdoutLength={}", toolName, stdout.length());
            return stdout;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.MCP_5XX, "failed to execute MCP tool: " + toolName + " | " + error.getMessage());
        }
    }

    private String executeViaDaemon(String toolName, Object payload, int timeoutMs) throws Exception {
        ensureDaemonStarted();

        String payloadJson = objectMapper.writeValueAsString(Map.of(
                "toolName", toolName,
                "payload", payload
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + MCP_BRIDGE_PORT + "/call"))
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new BusinessException(ErrorCode.MCP_5XX, "MCP daemon bridge failed: HTTP " + response.statusCode());
        }

        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        if (!Boolean.TRUE.equals(body.get("ok"))) {
            throw new BusinessException(ErrorCode.MCP_5XX, "MCP daemon bridge error: " + body.get("error"));
        }

        String stdout = String.valueOf(body.getOrDefault("stdout", ""));
        if (stdout.isBlank()) {
            throw new BusinessException(ErrorCode.MCP_SCHEMA_ERROR, "empty response from MCP daemon bridge: " + toolName);
        }

        log.info("mcp tool response tool={} stdoutLength={}", toolName, stdout.length());
        return stdout;
    }

    private void ensureDaemonStarted() throws Exception {
        if (isDaemonHealthy()) {
            return;
        }

        Path helperPath = Path.of(properties.helperScript());
        Path daemonScript = helperPath.getParent().resolve("mcp-tool-daemon.mjs");

        ProcessBuilder processBuilder = new ProcessBuilder(
                properties.serverCommand(),
                daemonScript.toString()
        );
        processBuilder.environment().put("MCP_PROJECT_ROOT", properties.projectRoot());
        processBuilder.environment().put("MCP_SERVER_COMMAND", properties.serverCommand());
        processBuilder.environment().put("MCP_SERVER_ENTRY", properties.serverEntry());
        processBuilder.environment().put("MCP_BRIDGE_PORT", String.valueOf(MCP_BRIDGE_PORT));
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.start();

        long deadline = System.currentTimeMillis() + 4000L;
        while (System.currentTimeMillis() < deadline) {
            if (isDaemonHealthy()) {
                return;
            }
            Thread.sleep(150L);
        }
        throw new BusinessException(ErrorCode.MCP_TIMEOUT, "MCP daemon bridge startup timeout");
    }

    private boolean isDaemonHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + MCP_BRIDGE_PORT + "/health"))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readStream(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private McpRecommendationPayload fallbackRecommendations(McpHybridRecommendationArgs request, Throwable throwable) {
        log.warn("mcp recommendations fallback request={} cause={}",
                safeJson(request), throwable == null ? "unknown" : throwable.getMessage());
        return new McpRecommendationPayload(0, Map.of(), List.of());
    }

    private McpLibrarySearchPayload fallbackSearch(McpSearchLibraryArgs request, Throwable throwable) {
        log.warn("mcp search fallback request={} cause={}",
                safeJson(request), throwable == null ? "unknown" : throwable.getMessage());
        return new McpLibrarySearchPayload(0, List.of());
    }

    private McpListeningHistoryPayload fallbackHistory(McpListeningHistoryArgs request, Throwable throwable) {
        log.warn("mcp history fallback request={} cause={}",
                safeJson(request), throwable == null ? "unknown" : throwable.getMessage());
        return new McpListeningHistoryPayload(List.of(), 0, 0);
    }

    private Map<String, Object> fallbackAnalyzeGenre(String genre, Throwable throwable) {
        log.warn("mcp analyzeGenre fallback genre={} cause={}",
                genre, throwable == null ? "unknown" : throwable.getMessage());
        return Map.of("genre", genre, "descriptors", List.of(), "related_genres", List.of(), "library_count", 0);
    }

    private Map<String, Object> fallbackFindSimilarGenres(String genre, int maxResults, Throwable throwable) {
        log.warn("mcp findSimilarGenres fallback genre={} maxResults={} cause={}",
                genre, maxResults, throwable == null ? "unknown" : throwable.getMessage());
        return Map.of("genre", genre, "similar_genres", List.of());
    }

    private Map<String, Object> fallbackLibraryStats(Throwable throwable) {
        log.warn("mcp libraryStats fallback cause={}",
                throwable == null ? "unknown" : throwable.getMessage());
        return Map.of();
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }
}
