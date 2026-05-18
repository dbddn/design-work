package com.music.reco.mcp.client;

import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.mcp.dto.McpLibrarySearchPayload;
import com.music.reco.mcp.dto.McpListeningHistoryArgs;
import com.music.reco.mcp.dto.McpListeningHistoryPayload;
import com.music.reco.mcp.dto.McpRecommendationPayload;
import com.music.reco.mcp.dto.McpSearchLibraryArgs;

import java.util.Map;

public interface McpClient {
    McpRecommendationPayload getRecommendations(McpHybridRecommendationArgs request);

    default McpRecommendationPayload getRecommendations(McpHybridRecommendationArgs request, int timeoutMs) {
        return getRecommendations(request);
    }

    McpLibrarySearchPayload searchLibrary(McpSearchLibraryArgs request);

    McpListeningHistoryPayload getListeningHistory(McpListeningHistoryArgs request);

    Map<String, Object> analyzeGenre(String genre);

    Map<String, Object> findSimilarGenres(String genre, int maxResults);

    Map<String, Object> libraryStats();
}
