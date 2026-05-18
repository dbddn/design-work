package com.music.reco.assistant.dto;

import com.music.reco.music.dto.TrackDto;

public record AssistantPlaylistTrackDto(
        TrackDto track,
        String reason
) {
}
