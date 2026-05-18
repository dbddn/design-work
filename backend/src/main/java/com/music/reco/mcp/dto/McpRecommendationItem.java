package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpRecommendationItem(
        String artist,
        String album,
        List<String> genres,
        @JsonProperty("secondary_genres")
        List<String> secondaryGenres,
        List<String> descriptors,
        @JsonProperty("similarity_score")
        Double similarityScore,
        @JsonProperty("metadata_score")
        Double metadataScore,
        @JsonProperty("combined_score")
        Double combinedScore,
        String explanation
) {
}
