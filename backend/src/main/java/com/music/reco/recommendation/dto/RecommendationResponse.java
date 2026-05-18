package com.music.reco.recommendation.dto;

import com.music.reco.music.dto.TrackDto;

import java.util.List;
import java.util.Map;

public record RecommendationResponse(
        String requestId,
        String strategyVersion,
        Map<String, Double> hybridWeight,
        List<TrackDto> tracks,
        String userStage,
        boolean guest,
        boolean onboardingRequired,
        List<String> selectedGenres,
        List<String> onboardingOptions,
        int playCount30d,
        String summary
) {
}
