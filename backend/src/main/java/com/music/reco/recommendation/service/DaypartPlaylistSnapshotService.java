package com.music.reco.recommendation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.recommendation.dto.TimeSlotPlaylistDto;
import com.music.reco.recommendation.strategy.HybridWeightStrategyService;
import com.music.reco.recommendation.strategy.StrategySnapshot;
import com.music.reco.user.dto.UserStatsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DaypartPlaylistSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(DaypartPlaylistSnapshotService.class);
    private static final List<String> SLOT_ORDER = List.of("morning", "afternoon", "evening", "midnight");

    private final LegacyJdbcRepository legacyJdbcRepository;
    private final HybridWeightStrategyService strategyService;
    private final TimeSlotPlaylistOrchestratorService orchestratorService;
    private final UserPreferenceContextService userPreferenceContextService;
    private final ObjectMapper objectMapper;

    public DaypartPlaylistSnapshotService(LegacyJdbcRepository legacyJdbcRepository,
                                          HybridWeightStrategyService strategyService,
                                          TimeSlotPlaylistOrchestratorService orchestratorService,
                                          UserPreferenceContextService userPreferenceContextService,
                                          ObjectMapper objectMapper) {
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.strategyService = strategyService;
        this.orchestratorService = orchestratorService;
        this.userPreferenceContextService = userPreferenceContextService;
        this.objectMapper = objectMapper;
    }

    public List<TimeSlotPlaylistDto> loadStoredPlaylists(String userId, String scene, String emotion, int limit) {
        String sceneTag = buildSceneTag(scene, emotion);
        Map<String, TimeSlotPlaylistDto> playlistMap = new LinkedHashMap<>();
        for (LegacyJdbcRepository.StoredPlaylistRecord record : legacyJdbcRepository.listDaypartPlaylists(userId, sceneTag)) {
            String slotKey = safe(record.moodTag());
            if (slotKey.isBlank() || playlistMap.containsKey(slotKey)) {
                continue;
            }
            playlistMap.put(slotKey, toPlaylistDto(record, limit));
        }

        return SLOT_ORDER.stream()
                .map(playlistMap::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public boolean hasCompleteStoredPlaylists(String userId, String scene, String emotion, int limit) {
        List<TimeSlotPlaylistDto> stored = loadStoredPlaylists(userId, scene, emotion, limit);
        boolean noCrossSlotOverlap = hasNoCrossSlotOverlap(stored);
        boolean complete = stored.size() == SLOT_ORDER.size()
                && stored.stream().allMatch(playlist -> playlist.tracks() != null && playlist.tracks().size() >= Math.min(limit, 10))
                && noCrossSlotOverlap;
        log.info("daypart cache check userId={} scene={} emotion={} hit={} playlistCount={} requiredSlots={} minTracks={} noCrossSlotOverlap={}",
                userId, scene, emotion, complete, stored.size(), SLOT_ORDER.size(), Math.min(limit, 10), noCrossSlotOverlap);
        return complete;
    }

    private boolean hasNoCrossSlotOverlap(List<TimeSlotPlaylistDto> playlists) {
        Set<Long> usedTrackIds = new LinkedHashSet<>();
        for (TimeSlotPlaylistDto playlist : playlists) {
            for (TrackDto track : safeTracks(playlist.tracks())) {
                if (track == null || track.id() == null) {
                    continue;
                }
                if (!usedTrackIds.add(track.id())) {
                    return false;
                }
            }
        }
        return true;
    }

    public List<TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult> generateAndStore(String requestId,
                                                                                                  String userId,
                                                                                                  String scene,
                                                                                                  String emotion,
                                                                                                  int limit) {
        UserStatsResponse stats = legacyJdbcRepository.buildUserStats(userId);
        List<String> selectedGenres = legacyJdbcRepository.findOnboardingGenres(userId);
        StrategySnapshot snapshot = strategyService.generate(userId, scene, emotion, stats.playCount30d(), stats.skipRate30d());
        var context = userPreferenceContextService.build(userId, stats, selectedGenres);
        List<TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult> buildResults =
                orchestratorService.buildPlaylists(requestId, userId, scene, emotion, limit, snapshot, context);
        savePlaylists(userId, scene, emotion, buildResults);
        return buildResults;
    }

    public List<TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult> buildFastView(String requestId,
                                                                                               String userId,
                                                                                               String scene,
                                                                                               String emotion,
                                                                                               int limit) {
        UserStatsResponse stats = legacyJdbcRepository.buildUserStats(userId);
        List<String> selectedGenres = legacyJdbcRepository.findOnboardingGenres(userId);
        StrategySnapshot snapshot = strategyService.generate(userId, scene, emotion, stats.playCount30d(), stats.skipRate30d());
        var context = userPreferenceContextService.build(userId, stats, selectedGenres);
        return orchestratorService.buildPlaylistsMcpOnly(requestId, userId, scene, emotion, limit, snapshot, context);
    }

    public void savePlaylists(String userId,
                              String scene,
                              String emotion,
                              List<TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult> buildResults) {
        String sceneTag = buildSceneTag(scene, emotion);
        for (TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult buildResult : buildResults) {
            TimeSlotPlaylistDto playlist = buildResult.playlist();
            String metadataJson = writeMetadata(playlist);
            Long playlistId = legacyJdbcRepository.findPlaylistIdByUserSceneAndMood(userId, sceneTag, playlist.key())
                    .orElseGet(() -> legacyJdbcRepository.createPlaylist(
                            userId,
                            playlist.playlistTitle(),
                            metadataJson,
                            playlist.key(),
                            sceneTag,
                            firstCoverUrl(playlist.tracks())
                    ));

            legacyJdbcRepository.updatePlaylistSnapshot(
                    playlistId,
                    playlist.playlistTitle(),
                    metadataJson,
                    playlist.key(),
                    sceneTag,
                    firstCoverUrl(playlist.tracks())
            );
            legacyJdbcRepository.clearPlaylistTracks(playlistId);
            for (TrackDto track : safeTracks(playlist.tracks())) {
                if (track != null && track.id() != null) {
                    legacyJdbcRepository.addTrackToPlaylist(userId, playlistId, track.id());
                }
            }
            log.info("daypart snapshot stored userId={} sceneTag={} timeSlot={} playlistId={} songCount={} aiSuccess={} fallback={}",
                    userId, sceneTag, playlist.key(), playlistId, safeTracks(playlist.tracks()).size(),
                    playlist.aiSuccess(), playlist.fallbackUsed());
        }
    }

    private TimeSlotPlaylistDto toPlaylistDto(LegacyJdbcRepository.StoredPlaylistRecord record, int limit) {
        Map<String, Object> metadata = readMetadata(record.description());
        List<TrackDto> tracks = legacyJdbcRepository.listPlaylistTracks(record.id(), Math.max(1, limit));
        return new TimeSlotPlaylistDto(
                safeString(metadata.get("key"), record.moodTag()),
                safeString(metadata.get("label"), record.moodTag()),
                safeString(metadata.get("mood"), ""),
                safeString(metadata.get("description"), ""),
                safeString(metadata.get("playlistTitle"), record.name()),
                safeString(metadata.get("playlistSubtitle"), ""),
                safeString(metadata.get("playlistReason"), ""),
                readStringList(metadata.get("tags")),
                tracks,
                Map.of(),
                safeBoolean(metadata.get("aiEnabled")),
                safeBoolean(metadata.get("aiSuccess")),
                safeBoolean(metadata.get("fallbackUsed")),
                safeString(metadata.get("fallbackReason"), null),
                tracks.size(),
                tracks.size()
        );
    }

    private String writeMetadata(TimeSlotPlaylistDto playlist) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", playlist.key());
        metadata.put("label", playlist.label());
        metadata.put("mood", playlist.mood());
        metadata.put("description", playlist.description());
        metadata.put("playlistTitle", playlist.playlistTitle());
        metadata.put("playlistSubtitle", playlist.playlistSubtitle());
        metadata.put("playlistReason", playlist.playlistReason());
        metadata.put("tags", playlist.tags());
        metadata.put("aiEnabled", playlist.aiEnabled());
        metadata.put("aiSuccess", playlist.aiSuccess());
        metadata.put("fallbackUsed", playlist.fallbackUsed());
        metadata.put("fallbackReason", playlist.fallbackReason());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception error) {
            log.warn("daypart snapshot metadata write failed key={} message={}",
                    playlist.key(), error.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> readMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            return Map.of();
        }
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(6)
                .toList();
    }

    private String firstCoverUrl(List<TrackDto> tracks) {
        return safeTracks(tracks).stream()
                .map(TrackDto::artworkUrl)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private List<TrackDto> safeTracks(List<TrackDto> tracks) {
        return tracks == null ? List.of() : tracks;
    }

    private String buildSceneTag(String scene, String emotion) {
        return "daypart:" + safe(scene) + ":" + safe(emotion);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private boolean safeBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
