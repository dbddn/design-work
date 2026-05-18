package com.music.reco.mcp.dto;

import java.util.List;

public record McpTrackDto(
        String id,
        String title,
        String artist,
        String album,
        String genre,
        String audioUrl,
        Double score,
        List<String> tags
) {
}
