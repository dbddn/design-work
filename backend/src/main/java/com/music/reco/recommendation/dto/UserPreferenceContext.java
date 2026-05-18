package com.music.reco.recommendation.dto;

import java.util.List;

public record UserPreferenceContext(
        List<String> topGenres,
        List<String> topArtists,
        List<String> topAlbums,
        List<String> recentAnchorAlbums,
        int playCount30d,
        double skipRate30d,
        boolean coldStart
) {
}
