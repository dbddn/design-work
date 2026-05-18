package com.music.reco.mcp.service;

import com.music.reco.mcp.dto.McpTrackDto;
import com.music.reco.music.dto.TrackDto;
import org.springframework.stereotype.Service;

@Service
public class McpResponseNormalizer {
    public TrackDto toTrackDto(McpTrackDto trackDto) {
        return new TrackDto(
                null,
                trackDto.id(),
                defaultStr(trackDto.title(), "Unknown"),
                defaultStr(trackDto.artist(), "Unknown"),
                trackDto.album(),
                trackDto.genre(),
                trackDto.audioUrl(),
                null,
                null,
                null,
                null,
                trackDto.score() == null ? 0.0 : trackDto.score(),
                "MCP");
    }

    private String defaultStr(String value, String def) {
        return value == null || value.isBlank() ? def : value;
    }
}
