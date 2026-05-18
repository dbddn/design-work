package com.music.reco.music.controller;

import com.music.reco.music.service.LocalSongAssetService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/media/songs")
public class LocalMediaController {
    private final LocalSongAssetService localSongAssetService;

    public LocalMediaController(LocalSongAssetService localSongAssetService) {
        this.localSongAssetService = localSongAssetService;
    }

    @GetMapping("/{trackId}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable Long trackId) {
        return localSongAssetService.readEmbeddedCover(trackId)
                .map(cover -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(12, TimeUnit.HOURS))
                        .contentType(MediaType.parseMediaType(cover.contentType()))
                        .body(cover.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
