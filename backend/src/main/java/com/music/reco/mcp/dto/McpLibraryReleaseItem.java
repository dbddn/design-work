package com.music.reco.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpLibraryReleaseItem(
        String id,
        String artist,
        String album,
        List<String> genres,
        @JsonProperty("secondary_genres")
        List<String> secondaryGenres,
        List<String> descriptors,
        Double rating,
        @JsonProperty("play_count")
        Integer playCount
) {
}
