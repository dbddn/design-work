package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpLibrarySearchPayload(
        Integer count,
        List<McpLibraryReleaseItem> results
) {
}
