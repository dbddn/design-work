package com.music.reco.music.dto;

public record NeteaseToplistTrackDto(
        Long localTrackId,
        Long neteaseTrackId,
        String title,
        String artist,
        String album,
        String artworkUrl
) {
}
