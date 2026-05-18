package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpListeningHistoryArgs(
        Integer limit,
        @JsonProperty("days_back")
        Integer daysBack
) {
}
