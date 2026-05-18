package com.music.reco.recommendation.dto;

import java.util.List;

public record DaypartRecommendationResponse(
        String requestId,
        String strategyVersion,
        String userStage,
        boolean guest,
        boolean onboardingRequired,
        List<String> selectedGenres,
        List<String> onboardingOptions,
        int playCount30d,
        String summary,
        List<TimeSlotPlaylistDto> playlists
) {
}
