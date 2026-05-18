package com.music.reco.music.dto;

import java.util.List;

public record ArtistDetailDto(
        Long id,
        String name,
        String description,
        String avatarUrl,
        int page,
        int size,
        boolean hasMore,
        List<TrackDto> hotTracks
) {
}
