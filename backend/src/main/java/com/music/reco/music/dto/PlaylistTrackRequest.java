package com.music.reco.music.dto;

import jakarta.validation.constraints.NotNull;

public record PlaylistTrackRequest(
        @NotNull Long trackId
) {
}
