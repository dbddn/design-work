package com.music.reco.assistant.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record AssistantChatRequest(
        @NotBlank String message,
        List<AssistantMessageDto> history,
        String scene,
        String emotion
) {
}
