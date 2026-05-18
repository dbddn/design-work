package com.music.reco.music.dto;

import java.util.List;

public record TrackDto(
        Long id,
        String mcpTrackId,
        String title,
        String artist,
        String album,
        String genre,
        String audioUrl,
        String artworkUrl,
        Integer durationSec,
        String description,
        List<String> lyrics,
        Double score,
        String source
) {
}
