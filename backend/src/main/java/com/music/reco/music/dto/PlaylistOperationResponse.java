package com.music.reco.music.dto;

public record PlaylistOperationResponse(
        Long playlistId,
        Long userId,
        String status
) {
}
