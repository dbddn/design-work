package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpListeningHistoryPayload(
        @JsonProperty("recent_plays")
        List<Map<String, Object>> recentPlays,
        @JsonProperty("period_days")
        Integer periodDays,
        @JsonProperty("total_plays")
        Integer totalPlays
) {
}
