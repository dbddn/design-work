package com.music.reco.assistant.dto;

import jakarta.validation.constraints.NotBlank;

public record AssistantMessageDto(
        @NotBlank String role,
        @NotBlank String content
) {
}
