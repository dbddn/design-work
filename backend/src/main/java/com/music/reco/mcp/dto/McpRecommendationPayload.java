package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpRecommendationPayload(
        Integer count,
        Map<String, Object> query,
        List<McpRecommendationItem> recommendations
) {
}
