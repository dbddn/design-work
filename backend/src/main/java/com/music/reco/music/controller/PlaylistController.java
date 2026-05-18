package com.music.reco.music.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.music.dto.CreatePlaylistRequest;
import com.music.reco.music.dto.PlaylistCollectionDto;
import com.music.reco.music.dto.PlaylistFavoriteRequest;
import com.music.reco.music.dto.PlaylistOperationResponse;
import com.music.reco.music.dto.PlaylistTrackRequest;
import com.music.reco.music.service.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {
    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @PostMapping
    public ApiResponse<PlaylistOperationResponse> create(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                         @Valid @RequestBody CreatePlaylistRequest request) {
        return ApiResponse.ok(playlistService.create(userId, request.name()));
    }

    @GetMapping("/mine")
    public ApiResponse<PlaylistCollectionDto> mine(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return ApiResponse.ok(playlistService.mine(userId));
    }

    @PostMapping("/{id}/tracks")
    public ApiResponse<PlaylistOperationResponse> addTrack(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody PlaylistTrackRequest request) {
        return ApiResponse.ok(playlistService.addTrack(userId, id, request.trackId()));
    }

    @PostMapping("/{id}/favorite")
    public ApiResponse<PlaylistOperationResponse> favorite(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                           @PathVariable Long id,
                                                           @RequestBody(required = false) PlaylistFavoriteRequest request) {
        boolean favorite = request == null || request.favorite();
        return ApiResponse.ok(playlistService.favorite(userId, id, favorite));
    }
}
