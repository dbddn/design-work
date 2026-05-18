package com.music.reco.music.dto;

public record PlaylistSummaryDto(
        Long id,
        String name,
        String description,
        String coverUrl,
        String moodTag,
        String sceneTag,
        Integer songCount,
        Long favoriteCount,
        boolean favorited,
        boolean createdByCurrentUser
) {
}
