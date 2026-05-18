package com.music.reco.music.dto;

import java.time.Instant;

public record HistoryItemDto(
        Long id,
        Long trackId,
        String title,
        String artist,
        String album,
        String artworkUrl,
        String genre,
        Instant playedAt,
        Integer playDurationSec,
        Boolean completed,
        String sourceType
) {
}
