package com.music.reco.mcp.dto;

import java.util.List;

public record McpRecommendationsResponse(
        String requestId,
        List<McpTrackDto> recommendations
) {
}
