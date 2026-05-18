package com.music.reco.assistant.dto;

import java.util.List;

public record AssistantChatResponse(
        String reply,
        String playlistTitle,
        String playlistSummary,
        List<AssistantPlaylistTrackDto> playlist,
        String model,
        boolean usedFallback,
        List<String> reasoningSummary
) {
}
