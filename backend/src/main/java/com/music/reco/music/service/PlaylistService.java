package com.music.reco.music.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.PlaylistCollectionDto;
import com.music.reco.music.dto.PlaylistOperationResponse;
import org.springframework.stereotype.Service;

@Service
public class PlaylistService {
    private final LegacyJdbcRepository legacyJdbcRepository;

    public PlaylistService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public PlaylistOperationResponse create(String userId, String name) {
        Long playlistId = legacyJdbcRepository.createPlaylist(userId, name);
        return new PlaylistOperationResponse(playlistId, null, "created:" + name);
    }

    public PlaylistOperationResponse addTrack(String userId, Long playlistId, Long trackId) {
        legacyJdbcRepository.addTrackToPlaylist(userId, playlistId, trackId);
        return new PlaylistOperationResponse(playlistId, null, "track_added:" + trackId);
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
}
