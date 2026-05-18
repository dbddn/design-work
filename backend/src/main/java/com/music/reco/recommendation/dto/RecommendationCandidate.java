package com.music.reco.recommendation.dto;

import com.music.reco.music.dto.TrackDto;

import java.util.List;

public record RecommendationCandidate(
        Long musicId,
        TrackDto track,
        Double recallScore,
        Double similarityScore,
        Double metadataScore,
        String recallSource,
        String explanation,
        List<String> mcpGenres,
        List<String> mcpSecondaryGenres,
        List<String> mcpDescriptors
) {
}
