package com.music.reco.music.dto;

public record ArtistSummaryDto(
        Long id,
        String name,
        String description,
        String avatarUrl
) {
}
