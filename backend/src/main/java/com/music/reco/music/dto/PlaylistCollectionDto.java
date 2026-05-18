package com.music.reco.music.dto;

import java.util.List;

public record PlaylistCollectionDto(
        List<PlaylistSummaryDto> created,
        List<PlaylistSummaryDto> favorites
) {
}
