package com.music.reco.music.dto;

public record FavoriteTrackDto(
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
        Integer rating,
        String likedAt
) {
}
