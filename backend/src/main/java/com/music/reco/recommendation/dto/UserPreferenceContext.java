package com.music.reco.recommendation.dto;

import java.util.List;
import java.util.Map;

public record UserPreferenceContext(
        List<String> topGenres,
        List<String> topArtists,
        List<String> topAlbums,
        List<String> recentAnchorAlbums,
        int playCount30d,
        double skipRate30d,
        int feedbackCount,
        int positiveFeedbackCount,
        int negativeFeedbackCount,
        Map<String, List<String>> globalTimeSlotGenres,
        Map<String, List<String>> globalTimeSlotArtists,
        boolean coldStart
) {
}
