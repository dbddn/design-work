package com.music.reco.music.dto;

import java.util.List;

public record SearchResponse(
        int page,
        int size,
        long total,
        List<TrackDto> items,
        ArtistSummaryDto artistCard
) {
}
