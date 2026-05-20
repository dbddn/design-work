package com.music.reco.assistant.dto;

import com.music.reco.music.dto.TrackDto;

import java.util.List;

public record AssistantChatResponse(
        String reply,
        String assistantReply,
        String playlistTitle,
        String playlistSummary,
        String recommendationReason,
        List<AssistantPlaylistTrackDto> playlist,
        List<TrackDto> songs,
        List<String> tags,
        String model,
        boolean usedFallback,
        boolean fallback,
        List<String> reasoningSummary
) {
}
