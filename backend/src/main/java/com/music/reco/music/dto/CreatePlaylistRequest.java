package com.music.reco.music.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePlaylistRequest(
        @NotBlank String name
) {
}
