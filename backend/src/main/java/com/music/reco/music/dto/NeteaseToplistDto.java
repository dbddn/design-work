package com.music.reco.music.dto;

import java.util.List;

public record NeteaseToplistDto(
        Long id,
        String name,
        String coverImgUrl,
        String updateFrequency,
        List<NeteaseToplistTrackDto> tracks
) {
}

