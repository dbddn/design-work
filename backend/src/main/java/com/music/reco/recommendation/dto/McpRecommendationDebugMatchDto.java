package com.music.reco.recommendation.dto;

public record McpRecommendationDebugMatchDto(
        String artist,
        String album,
        String explanation,
        Double similarityScore,
        Double metadataScore,
        Double combinedScore,
        Long matchedMusicId,
        String matchedTitle,
        String matchedArtist,
        String matchedAlbum,
        String recallSource,
        boolean matched
) {
}
