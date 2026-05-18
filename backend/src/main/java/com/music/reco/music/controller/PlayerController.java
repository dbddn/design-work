package com.music.reco.music.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.music.dto.HistoryItemDto;
import com.music.reco.music.dto.PlayerEventRequest;
import com.music.reco.music.service.MusicService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class PlayerController {
    private final MusicService musicService;

    public PlayerController(MusicService musicService) {
        this.musicService = musicService;
    }

    @PostMapping("/api/player/events")
    public ApiResponse<Void> events(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                    @Valid @RequestBody PlayerEventRequest request) {
        musicService.recordEvent(userId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/history")
    public ApiResponse<List<HistoryItemDto>> history(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                     @RequestParam(defaultValue = "40") int limit) {
        return ApiResponse.ok(musicService.history(userId, limit));
    }
}
