package com.music.reco.recommendation.dto;

import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.mcp.dto.McpRecommendationPayload;
import com.music.reco.music.dto.TrackDto;

import java.util.List;

public record McpRecommendationDebugResponse(
        String userId,
        String scene,
        String emotion,
        int requestedLimit,
        boolean coldStart,
        double explorationRatio,
        McpHybridRecommendationArgs mappedArgs,
        McpRecommendationPayload rawMcpPayload,
        int rawRecommendationCount,
        int matchedCount,
        int unmatchedCount,
        boolean fallbackTriggered,
        String errorMessage,
        List<McpRecommendationDebugMatchDto> matches,
        List<TrackDto> fallbackTracks
) {
}
