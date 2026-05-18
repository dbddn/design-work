package com.music.reco.mcp.dto;

import java.util.Map;

public record McpRecommendationsRequest(
        Long userId,
        String scene,
        String emotion,
        int limit,
        Map<String, Double> hybridWeight,
        double explorationRatio
) {
}
