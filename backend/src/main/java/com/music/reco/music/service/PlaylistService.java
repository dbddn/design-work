package com.music.reco.music.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.PlaylistCollectionDto;
import com.music.reco.music.dto.PlaylistOperationResponse;
import com.music.reco.music.dto.TrackDto;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class PlaylistService {
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final TrackIdentityService trackIdentityService;

    public PlaylistService(LegacyJdbcRepository legacyJdbcRepository,
                           TrackIdentityService trackIdentityService) {
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.trackIdentityService = trackIdentityService;
    }

    public PlaylistOperationResponse create(String userId, String name) {
        String safeName = repairMojibake(name == null ? "" : name.trim());
        Long playlistId = legacyJdbcRepository.createPlaylist(userId, safeName);
        return new PlaylistOperationResponse(playlistId, null, "created");
    }

    public PlaylistOperationResponse addTrack(String userId, Long playlistId, Long trackId) {
        Long localTrackId = trackIdentityService.resolveLocalTrackId(trackId);
        legacyJdbcRepository.addTrackToPlaylist(userId, playlistId, localTrackId);
        return new PlaylistOperationResponse(playlistId, localTrackId, "track_added:" + localTrackId);
    }

    public PlaylistOperationResponse favorite(String userId, Long playlistId, boolean favorite) {
        legacyJdbcRepository.favoritePlaylist(userId, playlistId, favorite);
        return new PlaylistOperationResponse(playlistId, null, favorite ? "favorited" : "unfavorited");
    }

    public PlaylistCollectionDto mine(String userId) {
        return new PlaylistCollectionDto(
                legacyJdbcRepository.listCreatedPlaylists(userId),
                legacyJdbcRepository.listFavoritedPlaylists(userId)
        );
    }

    public List<TrackDto> tracks(String userId, Long playlistId, int limit) {
        return legacyJdbcRepository.listPlaylistTracks(playlistId, Math.max(1, Math.min(limit, 200)));
    }

    private String repairMojibake(String value) {
        if (value == null || value.isBlank()) {
            return "未命名歌单";
        }
        if (!value.matches(".*[ÃÂäåæçèé].*")) {
            return value;
        }
        try {
            String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return repaired.isBlank() ? value : repaired;
        } catch (Exception ignored) {
            return value;
        }
    }
}
