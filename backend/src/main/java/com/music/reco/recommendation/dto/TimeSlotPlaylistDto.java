package com.music.reco.recommendation.dto;

import com.music.reco.music.dto.TrackDto;

import java.util.List;
import java.util.Map;

public record TimeSlotPlaylistDto(
        String key,
        String label,
        String mood,
        String description,
        String playlistTitle,
        String playlistSubtitle,
        String playlistReason,
        List<String> tags,
        List<TrackDto> tracks,
        Map<String, String> explanations,
        boolean aiEnabled,
        boolean aiSuccess,
        boolean fallbackUsed,
        String fallbackReason,
        int candidateCount,
        int finalCount
) {
}
