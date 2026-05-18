package com.music.reco.recommendation.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.mcp.dto.McpLibraryReleaseItem;
import com.music.reco.mcp.dto.McpRecommendationItem;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.recommendation.dto.RecommendationCandidate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class McpCandidateToTrackMatcher {
    private final LegacyJdbcRepository legacyJdbcRepository;

    public McpCandidateToTrackMatcher(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public Optional<RecommendationCandidate> matchRecommendation(McpRecommendationItem item) {
        Optional<TrackDto> exact = legacyJdbcRepository.findTrackByArtistAndAlbum(item.artist(), item.album());
        if (exact.isPresent()) {
            return Optional.of(toCandidate(exact.get(), item.combinedScore(), item.similarityScore(), item.metadataScore(),
                    "mcp_exact", item.explanation(), item.genres(), item.secondaryGenres(), item.descriptors()));
        }

        List<TrackDto> fuzzy = legacyJdbcRepository.fuzzyFindTracksByArtistAndAlbum(item.artist(), item.album(), 1);
        if (!fuzzy.isEmpty()) {
            TrackDto track = fuzzy.get(0);
            return Optional.of(toCandidate(track, item.combinedScore(), item.similarityScore(), item.metadataScore(),
                    "mcp_fuzzy", item.explanation(), item.genres(), item.secondaryGenres(), item.descriptors()));
        }

        List<TrackDto> albumOnly = legacyJdbcRepository.findTopTracksByAlbum(item.album(), 1);
        if (!albumOnly.isEmpty()) {
            TrackDto track = albumOnly.get(0);
            return Optional.of(toCandidate(track, item.combinedScore(), item.similarityScore(), item.metadataScore(),
                    "mcp_album_only", item.explanation(), item.genres(), item.secondaryGenres(), item.descriptors()));
        }

        return Optional.empty();
    }

    public Optional<RecommendationCandidate> matchLibraryRelease(McpLibraryReleaseItem item) {
        Optional<TrackDto> exact = legacyJdbcRepository.findTrackByArtistAndAlbum(item.artist(), item.album());
        if (exact.isPresent()) {
            return Optional.of(toCandidate(exact.get(), item.rating(), null, null,
                    "mcp_library_exact", null, item.genres(), item.secondaryGenres(), item.descriptors()));
        }

        List<TrackDto> fuzzy = legacyJdbcRepository.fuzzyFindTracksByArtistAndAlbum(item.artist(), item.album(), 1);
        if (!fuzzy.isEmpty()) {
            TrackDto track = fuzzy.get(0);
            return Optional.of(toCandidate(track, item.rating(), null, null,
                    "mcp_library_fuzzy", null, item.genres(), item.secondaryGenres(), item.descriptors()));
        }

        return Optional.empty();
    }

    private RecommendationCandidate toCandidate(TrackDto track,
                                                Double recallScore,
                                                Double similarityScore,
                                                Double metadataScore,
                                                String recallSource,
                                                String explanation,
                                                List<String> genres,
                                                List<String> secondaryGenres,
                                                List<String> descriptors) {
        return new RecommendationCandidate(
                track.id(),
                track,
                recallScore == null ? 0.0d : recallScore,
                similarityScore,
                metadataScore,
                recallSource,
                explanation,
                genres == null ? List.of() : genres,
                secondaryGenres == null ? List.of() : secondaryGenres,
                descriptors == null ? List.of() : descriptors
        );
    }
}
