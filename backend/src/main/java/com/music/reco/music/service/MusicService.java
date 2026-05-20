package com.music.reco.music.service;

import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.FavoriteTrackDto;
import com.music.reco.music.dto.HistoryItemDto;
import com.music.reco.music.dto.MusicCommentDto;
import com.music.reco.music.dto.PlayerEventRequest;
import com.music.reco.music.dto.SearchResponse;
import com.music.reco.music.dto.ArtistDetailDto;
import com.music.reco.music.dto.ArtistSummaryDto;
import com.music.reco.music.dto.TrackDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MusicService {
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final NeteaseApiTrackFallbackService neteaseApiTrackFallbackService;
    private final NeteaseArtistImageService neteaseArtistImageService;

    public MusicService(LegacyJdbcRepository legacyJdbcRepository,
                        NeteaseApiTrackFallbackService neteaseApiTrackFallbackService,
                        NeteaseArtistImageService neteaseArtistImageService) {
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.neteaseApiTrackFallbackService = neteaseApiTrackFallbackService;
        this.neteaseArtistImageService = neteaseArtistImageService;
    }

    public SearchResponse search(String keyword, int page, int size) {
        List<TrackDto> items = legacyJdbcRepository.searchTracks(keyword, page, size);
        long total = legacyJdbcRepository.countTracks(keyword);
        ArtistSummaryDto artistCard = null;
        try {
            artistCard = legacyJdbcRepository.findArtistCard(keyword).orElse(null);
        } catch (Exception ignored) {
            artistCard = null;
        }
        return new SearchResponse(page, size, total, items, artistCard);
    }

    public ArtistDetailDto artistDetail(Long artistId, int page, int size) {
        ArtistDetailDto artist = legacyJdbcRepository.getArtistDetail(artistId, page, size);
        String avatarUrl = neteaseArtistImageService.resolveAvatarUrl(artist.name(), artist.avatarUrl());
        if (avatarUrl == artist.avatarUrl() || (avatarUrl != null && avatarUrl.equals(artist.avatarUrl()))) {
            return artist;
        }
        return new ArtistDetailDto(
                artist.id(),
                artist.name(),
                artist.description(),
                avatarUrl,
                artist.page(),
                artist.size(),
                artist.hasMore(),
                artist.hotTracks()
        );
    }

    public ArtistSummaryDto resolveArtist(String artistName) {
        ArtistSummaryDto artist = legacyJdbcRepository.findArtistCard(artistName)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "artist not found"));
        String avatarUrl = neteaseArtistImageService.resolveAvatarUrl(artist.name(), artist.avatarUrl());
        return new ArtistSummaryDto(artist.id(), artist.name(), artist.description(), avatarUrl);
    }

    public TrackDto detail(Long id) {
        TrackDto track = legacyJdbcRepository.findTrack(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "track not found"));
        return neteaseApiTrackFallbackService.enrichTrack(track, true);
    }

    public TrackDto refreshPlayback(Long id) {
        TrackDto track = legacyJdbcRepository.findTrack(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "track not found"));
        return neteaseApiTrackFallbackService.refreshTrack(track, false);
    }

    public List<MusicCommentDto> comments(Long trackId) {
        return legacyJdbcRepository.listMusicComments(trackId);
    }

    public MusicCommentDto createComment(Long trackId, String userId, String content) {
        return legacyJdbcRepository.createMusicComment(trackId, userId, content);
    }

    public void recordEvent(String userId, PlayerEventRequest request) {
        legacyJdbcRepository.insertHistory(
                userId,
                request.trackId(),
                request.progressSec(),
                Boolean.TRUE.equals(request.completed()),
                request.eventType()
        );
    }

    public List<HistoryItemDto> history(String userId, int limit) {
        return legacyJdbcRepository.history(userId, limit);
    }

    public List<FavoriteTrackDto> favorites(String userId, int limit) {
        return legacyJdbcRepository.listFavoriteTracks(userId, limit);
    }
}
