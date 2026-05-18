package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpHybridRecommendationArgs(
        @JsonProperty("similar_to")
        List<String> similarTo,
        List<String> between,
        List<String> genres,
        @JsonProperty("secondary_genres")
        List<String> secondaryGenres,
        List<String> descriptors,
        @JsonProperty("min_rating")
        Double minRating,
        @JsonProperty("max_results")
        Integer maxResults,
        @JsonProperty("hybrid_weight")
        Double hybridWeight
) {
}
