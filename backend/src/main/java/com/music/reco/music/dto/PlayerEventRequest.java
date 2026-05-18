package com.music.reco.music.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlayerEventRequest(
        @NotNull Long trackId,
        @NotBlank String eventType,
        Integer progressSec,
        Boolean completed
) {
}
