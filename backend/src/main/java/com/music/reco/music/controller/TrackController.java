package com.music.reco.music.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.music.dto.ArtistDetailDto;
import com.music.reco.music.dto.ArtistSummaryDto;
import com.music.reco.music.dto.CreateCommentRequest;
import com.music.reco.music.dto.FavoriteTrackDto;
import com.music.reco.music.dto.MusicCommentDto;
import com.music.reco.music.dto.SearchResponse;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.music.service.MusicService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tracks")
public class TrackController {
    private final MusicService musicService;

    public TrackController(MusicService musicService) {
        this.musicService = musicService;
    }

    @GetMapping("/search")
    public ApiResponse<SearchResponse> search(@RequestParam("q") String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(musicService.search(q, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<TrackDto> detail(@PathVariable Long id) {
        return ApiResponse.ok(musicService.detail(id));
    }

    @PostMapping("/{id}/refresh-playback")
    public ApiResponse<TrackDto> refreshPlayback(@PathVariable Long id) {
        return ApiResponse.ok(musicService.refreshPlayback(id));
    }

    @GetMapping("/{id}/comments")
    public ApiResponse<List<MusicCommentDto>> comments(@PathVariable Long id) {
        return ApiResponse.ok(musicService.comments(id));
    }

    @GetMapping("/favorites")
    public ApiResponse<List<FavoriteTrackDto>> favorites(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                         @RequestParam(defaultValue = "40") int limit) {
        return ApiResponse.ok(musicService.favorites(userId, limit));
    }

    @PostMapping("/{id}/comments")
    public ApiResponse<MusicCommentDto> createComment(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.ok(musicService.createComment(id, userId, request.content()));
    }

    @GetMapping("/artists/{artistId}")
    public ApiResponse<ArtistDetailDto> artistDetail(@PathVariable Long artistId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(musicService.artistDetail(artistId, page, size));
    }

    @GetMapping("/artists/resolve")
    public ApiResponse<ArtistSummaryDto> resolveArtist(@RequestParam("name") String name) {
        return ApiResponse.ok(musicService.resolveArtist(name));
    }
}
