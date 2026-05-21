package com.music.reco.recommendation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.assistant.dto.AiCompletionResult;
import com.music.reco.assistant.dto.AiPromptMessage;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.mcp.client.McpClient;
import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.mcp.dto.McpLibraryReleaseItem;
import com.music.reco.mcp.dto.McpLibrarySearchPayload;
import com.music.reco.mcp.dto.McpRecommendationItem;
import com.music.reco.mcp.dto.McpRecommendationPayload;
import com.music.reco.mcp.dto.McpSearchLibraryArgs;
import com.music.reco.music.dto.FavoriteTrackDto;
import com.music.reco.music.dto.HistoryItemDto;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.recommendation.dto.RecommendationCandidate;
import com.music.reco.recommendation.dto.TimeSlotPlaylistDto;
import com.music.reco.recommendation.dto.UserPreferenceContext;
import com.music.reco.recommendation.strategy.StrategySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
public class TimeSlotPlaylistOrchestratorService {
        private static final Logger log = LoggerFactory.getLogger(TimeSlotPlaylistOrchestratorService.class);
        private static final int MIN_CANDIDATE_LIMIT = 10;
        private static final int DAYPART_MCP_TIMEOUT_MS = 1800;
        private static final Semaphore DAYPART_AI_SEMAPHORE = new Semaphore(1);
        private static final List<TimeSlotDefinition> TIME_SLOTS = List.of(
                        new TimeSlotDefinition(
                                        "morning",
                                        "上午",
                                        "清醒启动",
                                        "适合通勤、学习启动和逐步提神的早间播放。",
                                        "上午提神",
                                        List.of("清醒", "提神", "通勤", "学习启动")),
                        new TimeSlotDefinition(
                                        "afternoon",
                                        "下午",
                                        "轻松专注",
                                        "适合午后保持稳定节奏，轻松但不松散。",
                                        "下午稳态",
                                        List.of("轻松", "专注", "稳定节奏", "陪伴工作")),
                        new TimeSlotDefinition(
                                        "evening",
                                        "晚上",
                                        "松弛陪伴",
                                        "适合傍晚到夜间的放松、舒缓与陪伴。",
                                        "夜晚放松",
                                        List.of("放松", "舒缓", "陪伴", "夜色氛围")),
                        new TimeSlotDefinition(
                                        "midnight",
                                        "深夜",
                                        "沉浸低刺激",
                                        "适合安静收束注意力，保留沉浸感与低刺激体验。",
                                        "深夜沉浸",
                                        List.of("沉浸", "安静", "低刺激", "夜深人静")));

        private final LegacyJdbcRepository legacyJdbcRepository;
        private final McpClient mcpClient;
        private final McpQueryMappingService mcpQueryMappingService;
        private final McpCandidateToTrackMatcher mcpCandidateToTrackMatcher;
        private final DaypartLlmClientService daypartLlmClientService;
        private final ObjectMapper objectMapper;

        public TimeSlotPlaylistOrchestratorService(LegacyJdbcRepository legacyJdbcRepository,
                        McpClient mcpClient,
                        McpQueryMappingService mcpQueryMappingService,
                        McpCandidateToTrackMatcher mcpCandidateToTrackMatcher,
                        DaypartLlmClientService daypartLlmClientService,
                        ObjectMapper objectMapper) {
                this.legacyJdbcRepository = legacyJdbcRepository;
                this.mcpClient = mcpClient;
                this.mcpQueryMappingService = mcpQueryMappingService;
                this.mcpCandidateToTrackMatcher = mcpCandidateToTrackMatcher;
                this.daypartLlmClientService = daypartLlmClientService;
                this.objectMapper = objectMapper;
        }

        public List<TimeSlotPlaylistBuildResult> buildPlaylists(String requestId,
                        String userId,
                        String scene,
                        String emotion,
                        int limit,
                        StrategySnapshot snapshot,
                        UserPreferenceContext context) {
                log.info("daypart build start requestId={} userId={} scene={} emotion={} limit={}",
                                requestId, userId, scene, emotion, limit);
                List<HistoryItemDto> recentHistory = safeList(legacyJdbcRepository.history(userId, 10));
                List<FavoriteTrackDto> favoriteTracks = safeList(legacyJdbcRepository.listFavoriteTracks(userId, 6));
                UserSignalBundle userSignals = new UserSignalBundle(recentHistory, favoriteTracks);

                Set<Long> usedTrackIds = new LinkedHashSet<>();
                List<TimeSlotPlaylistBuildResult> results = new ArrayList<>();
                for (TimeSlotDefinition slot : TIME_SLOTS) {
                        TimeSlotPlaylistBuildResult result = buildSinglePlaylist(
                                        requestId, userId, slot, scene, emotion, limit, snapshot, context, userSignals,
                                        usedTrackIds);
                        results.add(result);
                }
                return results;
        }

        public List<TimeSlotPlaylistBuildResult> buildPlaylistsMcpOnly(String requestId,
                        String userId,
                        String scene,
                        String emotion,
                        int limit,
                        StrategySnapshot snapshot,
                        UserPreferenceContext context) {
                log.info("daypart fast build start requestId={} userId={} scene={} emotion={} limit={}",
                                requestId, userId, scene, emotion, limit);
                List<HistoryItemDto> recentHistory = safeList(legacyJdbcRepository.history(userId, 10));
                List<FavoriteTrackDto> favoriteTracks = safeList(legacyJdbcRepository.listFavoriteTracks(userId, 6));
                UserSignalBundle userSignals = new UserSignalBundle(recentHistory, favoriteTracks);

                Set<Long> usedTrackIds = new LinkedHashSet<>();
                List<TimeSlotPlaylistBuildResult> results = new ArrayList<>();
                for (TimeSlotDefinition slot : TIME_SLOTS) {
                        TimeSlotPlaylistBuildResult result = buildSinglePlaylistMcpOnly(
                                        requestId, userId, slot, scene, emotion, limit, snapshot, context, userSignals,
                                        usedTrackIds);
                        results.add(result);
                }
                return results;
        }

        private TimeSlotPlaylistBuildResult buildSinglePlaylist(String requestId,
                        String userId,
                        TimeSlotDefinition slot,
                        String scene,
                        String emotion,
                        int limit,
                        StrategySnapshot snapshot,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        Set<Long> usedTrackIds) {
                try {
                        int recallLimit = Math.max(limit, limit * TIME_SLOTS.size());
                        CandidateRecallResult recall = recallCandidates(requestId, userId, slot, scene, emotion, recallLimit,
                                        snapshot, context);
                        RefinementResult refinement = refinePlaylist(requestId, slot, recall, context, userSignals, snapshot, limit);
                        List<TrackDto> directRecallTracks = recall.candidates().stream()
                                        .map(RecommendationCandidate::track)
                                        .toList();

                        List<TrackDto> preferredTracks = refinement.tracks().isEmpty()
                                        ? directRecallTracks
                                        : refinement.tracks();
                        int beforeDedupeCount = preferredTracks.size();
                        List<TrackDto> finalTracks = selectAndReserveUniqueTracks(preferredTracks, usedTrackIds, limit);
                        int crossSlotDuplicateCount = Math.max(0, beforeDedupeCount - finalTracks.size());

                        String fallbackReason = refinement.fallbackReason();
                        boolean fallbackUsed = fallbackReason != null && !fallbackReason.isBlank();
                        TimeSlotPlaylistDto playlist = new TimeSlotPlaylistDto(
                                        slot.key(),
                                        slot.label(),
                                        slot.mood(),
                                        slot.description(),
                                        firstNonBlank(refinement.playlistTitle(), defaultPlaylistTitle(slot)),
                                        firstNonBlank(refinement.playlistSubtitle(), defaultPlaylistSubtitle(slot)),
                                        firstNonBlank(refinement.playlistReason(),
                                                        defaultReasonLong(slot, context, userSignals, fallbackUsed)),
                                        mergeTags(slot.tags(), refinement.tags()),
                                        finalTracks,
                                        refinement.explanations(),
                                        daypartLlmClientService.isEnabled(),
                                        refinement.aiSuccess(),
                                        fallbackUsed,
                                        fallbackReason,
                                        recall.mcpCandidateCount(),
                                        finalTracks.size());

                        log.info("daypart slot built requestId={} key={} tracks={} mcpCandidates={} crossSlotDuplicatesRemoved={} aiAttempted={} aiSuccess={} fallbackReason={}",
                                        requestId, slot.key(), finalTracks.size(), recall.mcpCandidateCount(),
                                        crossSlotDuplicateCount,
                                        refinement.aiAttempted(),
                                        refinement.aiSuccess(), fallbackReason);

                        return new TimeSlotPlaylistBuildResult(
                                        playlist,
                                        refinement.aiAttempted(),
                                        refinement.aiSuccess(),
                                        recall.mcpCandidateCount(),
                                        finalTracks.size(),
                                        fallbackReason);
                } catch (Exception error) {
                        log.error("daypart slot failed requestId={} key={} message={}", requestId, slot.key(), error.getMessage(), error);
                        List<RecommendationCandidate> localFallback = localFallbackCandidates(
                                        userId, slot, scene, emotion, Math.max(MIN_CANDIDATE_LIMIT, limit), context);
                        List<TrackDto> fallbackTracks = localFallback.stream()
                                        .map(RecommendationCandidate::track)
                                        .toList();
                        fallbackTracks = selectAndReserveUniqueTracks(fallbackTracks, usedTrackIds, limit);
                        TimeSlotPlaylistDto playlist = new TimeSlotPlaylistDto(
                                        slot.key(),
                                        slot.label(),
                                        slot.mood(),
                                        slot.description(),
                                        slot.label() + "个性化歌单",
                                        slot.goal(),
                                        defaultReasonLong(slot, context, userSignals, true),
                                        slot.tags(),
                                        fallbackTracks,
                                        Map.of(),
                                        daypartLlmClientService.isEnabled(),
                                        false,
                                        true,
                                        "DAYPART_SLOT_FAILED_USE_LOCAL",
                                        0,
                                        fallbackTracks.size());
                        return new TimeSlotPlaylistBuildResult(
                                        playlist,
                                        true,
                                        false,
                                        0,
                                        fallbackTracks.size(),
                                        "DAYPART_SLOT_FAILED_USE_LOCAL");
                }
        }

        private TimeSlotPlaylistBuildResult buildSinglePlaylistMcpOnly(String requestId,
                        String userId,
                        TimeSlotDefinition slot,
                        String scene,
                        String emotion,
                        int limit,
                        StrategySnapshot snapshot,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        Set<Long> usedTrackIds) {
                int recallLimit = Math.max(limit, limit * TIME_SLOTS.size());
                CandidateRecallResult recall = recallCandidates(requestId, userId, slot, scene, emotion, recallLimit, snapshot,
                                context);
                List<TrackDto> preferredTracks = recall.candidates().stream()
                                .map(RecommendationCandidate::track)
                                .toList();
                List<TrackDto> finalTracks = selectAndReserveUniqueTracks(preferredTracks, usedTrackIds, limit);
                int crossSlotDuplicateCount = Math.max(0, preferredTracks.size() - finalTracks.size());
                boolean usedLocalFallback = !recall.hasMcpCandidates();
                String fallbackReason = usedLocalFallback
                                ? "NO_MCP_CANDIDATES_USE_LOCAL"
                                : "PERSISTED_PLAYLIST_PENDING_USE_MCP";

                TimeSlotPlaylistDto playlist = new TimeSlotPlaylistDto(
                                slot.key(),
                                slot.label(),
                                slot.mood(),
                                slot.description(),
                                defaultPlaylistTitle(slot),
                                defaultPlaylistSubtitle(slot),
                                defaultReasonLong(slot, context, userSignals, usedLocalFallback),
                                slot.tags(),
                                finalTracks,
                                Map.of(),
                                daypartLlmClientService.isEnabled(),
                                false,
                                usedLocalFallback,
                                fallbackReason,
                                recall.mcpCandidateCount(),
                                finalTracks.size());

                log.info("daypart fast slot built requestId={} key={} tracks={} mcpCandidates={} crossSlotDuplicatesRemoved={} fallbackReason={}",
                                requestId, slot.key(), finalTracks.size(), recall.mcpCandidateCount(),
                                crossSlotDuplicateCount, fallbackReason);

                return new TimeSlotPlaylistBuildResult(
                                playlist,
                                false,
                                false,
                                recall.mcpCandidateCount(),
                                finalTracks.size(),
                                fallbackReason);
        }

        private List<TrackDto> selectAndReserveUniqueTracks(List<TrackDto> tracks, Set<Long> usedTrackIds, int limit) {
                List<TrackDto> selected = new ArrayList<>();
                Set<Long> selectedIds = new LinkedHashSet<>();
                for (TrackDto track : safeList(tracks)) {
                        if (track == null || track.id() == null || usedTrackIds.contains(track.id())
                                        || selectedIds.contains(track.id())) {
                                continue;
                        }
                        selected.add(track);
                        selectedIds.add(track.id());
                        if (selected.size() >= limit) {
                                break;
                        }
                }
                usedTrackIds.addAll(selectedIds);
                return selected;
        }

        private CandidateRecallResult recallCandidates(String requestId,
                        String userId,
                        TimeSlotDefinition slot,
                        String scene,
                        String emotion,
                        int limit,
                        StrategySnapshot snapshot,
                        UserPreferenceContext context) {
                int candidateLimit = Math.max(MIN_CANDIDATE_LIMIT, limit);
                McpHybridRecommendationArgs primaryArgs = mcpQueryMappingService.buildTimeSlotRecommendationArgs(
                                slot.key(),
                                scene,
                                emotion,
                                candidateLimit,
                                snapshot,
                                context);
                log.info("daypart mcp primary query requestId={} key={} userContext={} args={}",
                                requestId, slot.key(), summarizeUserContext(context), summarizeArgs(primaryArgs));
                RecommendationRecallBundle recommendationBundle = recallByRecommendations(requestId, slot.key(), primaryArgs,
                                candidateLimit);
                if (!recommendationBundle.candidates().isEmpty()) {
                        List<RecommendationCandidate> localFill = recommendationBundle.candidates().size() < candidateLimit
                                        ? localFallbackCandidates(userId, slot, scene, emotion, candidateLimit, context)
                                        : List.of();
                        List<RecommendationCandidate> finalCandidates = applyDiversity(
                                        mergeCandidates(recommendationBundle.candidates(), localFill),
                                        candidateLimit);
                        int fillCount = Math.max(0, finalCandidates.size() - recommendationBundle.candidates().size());
                        List<McpRecommendationItem> rawItems = recommendationBundle.rawItems();
                        Map<String, List<String>> insights = rawItems.isEmpty()
                                        ? buildGenreInsightsFromCandidates(finalCandidates)
                                        : buildGenreInsights(rawItems);
                        log.info("daypart mcp recall merged requestId={} key={} source=get_recommendations rawItems={} localMatched={} fillCount={} diversifiedCandidates={}",
                                        requestId, slot.key(), rawItems.size(), recommendationBundle.candidates().size(),
                                        fillCount, finalCandidates.size());
                        return new CandidateRecallResult(
                                        finalCandidates,
                                        finalCandidates.size(),
                                        rawItems,
                                        insights);
                }

                List<RecommendationCandidate> libraryCandidates = recallBySearchLibrary(requestId, slot.key(), primaryArgs,
                                candidateLimit);
                if (!libraryCandidates.isEmpty()) {
                        List<RecommendationCandidate> localFill = libraryCandidates.size() < candidateLimit
                                        ? localFallbackCandidates(userId, slot, scene, emotion, candidateLimit, context)
                                        : List.of();
                        List<RecommendationCandidate> finalCandidates = applyDiversity(
                                        mergeCandidates(libraryCandidates, localFill),
                                        candidateLimit);
                        int fillCount = Math.max(0, finalCandidates.size() - libraryCandidates.size());
                        log.info("daypart mcp recall merged requestId={} key={} source=search_library localMatched={} fillCount={} diversifiedCandidates={}",
                                        requestId, slot.key(), libraryCandidates.size(), fillCount, finalCandidates.size());
                        return new CandidateRecallResult(
                                        finalCandidates,
                                        libraryCandidates.size(),
                                        List.of(),
                                        buildGenreInsightsFromCandidates(finalCandidates));
                }

                List<RecommendationCandidate> localFallback = localFallbackCandidates(userId, slot, scene, emotion,
                                candidateLimit, context);
                List<RecommendationCandidate> diversifiedFallback = applyDiversity(localFallback, candidateLimit);
                log.info("daypart local fallback requestId={} key={} fallbackTriggered=true candidates={} diversifiedCandidates={}",
                                requestId, slot.key(), localFallback.size(), diversifiedFallback.size());
                return new CandidateRecallResult(diversifiedFallback, 0, List.of(), Map.of());
        }

        private RecommendationRecallBundle recallByRecommendations(String requestId,
                        String slotKey,
                        McpHybridRecommendationArgs primaryArgs,
                        int candidateLimit) {
                try {
                        log.info("daypart mcp recall request requestId={} key={} stage=primary args={}",
                                        requestId, slotKey, summarizeArgs(primaryArgs));
                        McpRecommendationPayload payload = mcpClient.getRecommendations(primaryArgs,
                                        DAYPART_MCP_TIMEOUT_MS);
                        List<McpRecommendationItem> rawItems = payload.recommendations() == null ? List.of()
                                        : payload.recommendations();
                        List<RecommendationCandidate> matchedCandidates = rawItems.stream()
                                        .map(mcpCandidateToTrackMatcher::matchRecommendation)
                                        .flatMap(Optional::stream)
                                        .collect(Collectors.collectingAndThen(
                                                        Collectors.toMap(
                                                                        RecommendationCandidate::musicId,
                                                                        candidate -> candidate,
                                                                        (left, right) -> left,
                                                                        LinkedHashMap::new),
                                                        map -> map.values().stream().limit(candidateLimit).toList()));

                        log.info("daypart mcp recall response requestId={} key={} stage=primary rawItems={} matchedCandidates={}",
                                        requestId, slotKey, rawItems.size(), matchedCandidates.size());

                        if (!matchedCandidates.isEmpty()) {
                                return new RecommendationRecallBundle(matchedCandidates, rawItems);
                        }
                } catch (Exception error) {
                        log.warn("daypart mcp recall failed requestId={} key={} stage=primary message={}",
                                        requestId, slotKey, error.getMessage());
                }
                return new RecommendationRecallBundle(List.of(), List.of());
        }

        private List<RecommendationCandidate> recallBySearchLibrary(String requestId,
                        String slotKey,
                        McpHybridRecommendationArgs primaryArgs,
                        int candidateLimit) {
                try {
                        McpSearchLibraryArgs searchArgs = new McpSearchLibraryArgs(
                                        null,
                                        null,
                                        primaryArgs.genres(),
                                        primaryArgs.secondaryGenres(),
                                        primaryArgs.descriptors(),
                                        primaryArgs.minRating(),
                                        candidateLimit);
                        log.info("daypart mcp recall request requestId={} key={} stage=search_library genres={} descriptors={} minRating={} maxResults={}",
                                        requestId, slotKey, searchArgs.genres(), searchArgs.descriptors(),
                                        searchArgs.minRating(), searchArgs.maxResults());
                        McpLibrarySearchPayload payload = mcpClient.searchLibrary(searchArgs);
                        List<McpLibraryReleaseItem> rawItems = payload.results() == null ? List.of() : payload.results();
                        List<RecommendationCandidate> matchedCandidates = rawItems.stream()
                                        .map(mcpCandidateToTrackMatcher::matchLibraryRelease)
                                        .flatMap(Optional::stream)
                                        .collect(Collectors.collectingAndThen(
                                                        Collectors.toMap(
                                                                        RecommendationCandidate::musicId,
                                                                        candidate -> candidate,
                                                                        (left, right) -> left,
                                                                        LinkedHashMap::new),
                                                        map -> map.values().stream().limit(candidateLimit).toList()));
                        log.info("daypart mcp recall response requestId={} key={} stage=search_library rawItems={} matchedCandidates={}",
                                        requestId, slotKey, rawItems.size(), matchedCandidates.size());
                        return matchedCandidates;
                } catch (Exception error) {
                        log.warn("daypart mcp recall failed requestId={} key={} stage=search_library message={}",
                                        requestId, slotKey, error.getMessage());
                        return List.of();
                }
        }

        private List<RecommendationCandidate> localFallbackCandidates(String userId,
                        TimeSlotDefinition slot,
                        String scene,
                        String emotion,
                        int limit,
                        UserPreferenceContext context) {
                List<TrackDto> slotTracks = safeList(legacyJdbcRepository.recommendTracks(
                                resolveFallbackScene(slot, scene),
                                resolveFallbackEmotion(slot, emotion),
                                limit));
                List<TrackDto> genreTracks = context.topGenres().isEmpty()
                                ? List.of()
                                : safeList(legacyJdbcRepository.recommendTracksByGenres(context.topGenres(), limit));

                LinkedHashMap<Long, TrackDto> mergedTracks = new LinkedHashMap<>();
                slotTracks.forEach(track -> {
                        if (track != null && track.id() != null) {
                                mergedTracks.putIfAbsent(track.id(), track);
                        }
                });
                genreTracks.forEach(track -> {
                        if (track != null && track.id() != null) {
                                mergedTracks.putIfAbsent(track.id(), track);
                        }
                });

                List<TrackDto> tracks = mergedTracks.values().stream()
                                .limit(limit)
                                .toList();

                return safeList(tracks).stream()
                                .map(track -> new RecommendationCandidate(
                                                track.id(),
                                                track,
                                                track.score() == null ? 0.0d : track.score(),
                                                null,
                                                null,
                                                "local_fallback",
                                                "本地偏好兜底",
                                                track.genre() == null || track.genre().isBlank() ? List.of()
                                                                : List.of(track.genre()),
                                                List.of(),
                                                List.of()))
                                .toList();
        }

        private List<RecommendationCandidate> mergeCandidates(List<RecommendationCandidate> primary,
                        List<RecommendationCandidate> fill) {
                LinkedHashMap<Long, RecommendationCandidate> merged = new LinkedHashMap<>();
                for (RecommendationCandidate candidate : safeList(primary)) {
                        if (candidate != null && candidate.musicId() != null) {
                                merged.putIfAbsent(candidate.musicId(), candidate);
                        }
                }
                for (RecommendationCandidate candidate : safeList(fill)) {
                        if (candidate != null && candidate.musicId() != null) {
                                merged.putIfAbsent(candidate.musicId(), candidate);
                        }
                }
                return new ArrayList<>(merged.values());
        }

        private List<RecommendationCandidate> applyDiversity(List<RecommendationCandidate> candidates, int limit) {
                Map<String, Integer> artistCount = new LinkedHashMap<>();
                Map<String, Integer> albumCount = new LinkedHashMap<>();
                Map<String, Integer> genreCount = new LinkedHashMap<>();
                List<RecommendationCandidate> selected = new ArrayList<>();
                List<RecommendationCandidate> deferred = new ArrayList<>();

                for (RecommendationCandidate candidate : safeList(candidates)) {
                        if (candidate == null || candidate.track() == null || candidate.musicId() == null) {
                                continue;
                        }
                        TrackDto track = candidate.track();
                        String artist = normalizeBucket(track.artist());
                        String album = normalizeBucket(track.album());
                        String genre = normalizeBucket(track.genre());
                        boolean overConcentrated = artistCount.getOrDefault(artist, 0) >= 2
                                        || albumCount.getOrDefault(album, 0) >= 2
                                        || genreCount.getOrDefault(genre, 0) >= 4;
                        if (overConcentrated) {
                                deferred.add(candidate);
                                continue;
                        }
                        selected.add(candidate);
                        artistCount.merge(artist, 1, Integer::sum);
                        albumCount.merge(album, 1, Integer::sum);
                        genreCount.merge(genre, 1, Integer::sum);
                        if (selected.size() >= limit) {
                                return selected;
                        }
                }

                for (RecommendationCandidate candidate : deferred) {
                        selected.add(candidate);
                        if (selected.size() >= limit) {
                                break;
                        }
                }
                return selected;
        }

        private RefinementResult refinePlaylist(String requestId,
                        TimeSlotDefinition slot,
                        CandidateRecallResult recall,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        StrategySnapshot snapshot,
                        int limit) {
                if (recall.candidates().isEmpty()) {
                        return RefinementResult.fallback("NO_CANDIDATES");
                }
                if (!recall.hasMcpCandidates()) {
                        log.info("daypart skip ai requestId={} key={} reason=NO_MCP_CANDIDATES candidateCount={}",
                                        requestId, slot.key(), recall.candidates().size());
                        return RefinementResult.fallback("NO_MCP_CANDIDATES_USE_LOCAL");
                }
                if (!daypartLlmClientService.isEnabled()) {
                        log.info("daypart skip ai requestId={} key={} reason=DAYPART_AI_DISABLED candidateCount={}",
                                        requestId, slot.key(), recall.candidates().size());
                        return RefinementResult.fallback("AI_DISABLED");
                }
                if (!DAYPART_AI_SEMAPHORE.tryAcquire()) {
                        log.info("daypart skip ai requestId={} key={} reason=DAYPART_AI_BUSY candidateCount={}",
                                        requestId, slot.key(), recall.candidates().size());
                        return RefinementResult.fallback("AI_BUSY_USE_MCP");
                }

                try {
                        AiCompletionResult completion = daypartLlmClientService.complete(
                                        requestId,
                                        slot.key(),
                                        buildPromptMessages(slot, recall, context, userSignals, snapshot, limit));
                        log.info("daypart ai decorate success requestId={} key={} contentChars={}",
                                        requestId,
                                        slot.key(),
                                        completion.content() == null ? 0 : completion.content().length());

                        return parseAiResult(slot, recall.candidates(), completion.content(), limit);
                } catch (Exception error) {
                        log.warn("daypart ai decorate failed requestId={} key={} fallback={} message={}",
                                        requestId,
                                        slot.key(),
                                        "AI_CALL_FAILED_USE_MCP",
                                        error.getMessage(),
                                        error);
                        return RefinementResult.fallback("AI_CALL_FAILED_USE_MCP");
                } finally {
                        DAYPART_AI_SEMAPHORE.release();
                }
        }

        private List<AiPromptMessage> buildPromptMessages(TimeSlotDefinition slot,
                        CandidateRecallResult recall,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        StrategySnapshot snapshot,
                        int limit) {
                return List.of(
                                new AiPromptMessage(
                                                "system",
                                                """
                                                                你是音乐推荐系统中的“时段歌单文案助手”。
                                                                你的职责不是选歌，也不是改动歌曲顺序。
                                                                给定一个已经确定好的候选歌单，请只输出歌单展示文案。
                                                                请严格输出 JSON，不要输出 markdown 代码块，不要输出额外说明。

                                                                JSON 结构如下：
                                                                {
                                                                "playlistTitle": "歌单标题",
                                                                "playlistSubtitle": "歌单副标题",
                                                                "playlistReason": "1到2句歌单说明",
                                                                "tags": ["标签1", "标签2", "标签3"]
                                                                }

                                                                规则：
                                                                1. 所有内容必须是简体中文。
                                                                2. 标题和副标题适合直接展示在前端。
                                                                3. playlistReason 要结合时段场景和用户偏好，但不要夸张，不要编造。
                                                                4. tags 保持 2 到 4 个，短一些，适合展示。
                                                                5. 不要返回 selectedTrackIds，不要返回 explanations。
                                                                """),
                                new AiPromptMessage(
                                                "user",
                                                buildUserPrompt(slot, recall, context, userSignals, snapshot, limit)),
                                new AiPromptMessage(
                                                "user",
                                                buildWeightAndPreferencePrompt(slot, context, snapshot)),
                                new AiPromptMessage(
                                                "user",
                                                buildRankingInstruction(slot, context, snapshot)));
        }

        private String buildWeightAndPreferencePrompt(TimeSlotDefinition slot,
                        UserPreferenceContext context,
                        StrategySnapshot snapshot) {
                return """
                                权重与偏好来源：
                                - popular=%s
                                - content=%s，来源=全体用户在 %s 时段的高频风格/歌手。
                                - scene=%s，来源=时段目标、场景和情绪配置。
                                - collaborative=%s，来源=当前用户自己的播放、收藏、评分、跳过和近期锚点。
                                - explorationRatio=%s
                                全体用户该时段偏好：genres=%s, artists=%s。
                                当前用户个人偏好：genres=%s, artists=%s, albums=%s, anchors=%s。
                                """.formatted(
                                formatNullable(snapshot.hybridWeight().get("popular")),
                                formatNullable(snapshot.hybridWeight().get("content")),
                                slot.key(),
                                formatNullable(snapshot.hybridWeight().get("scene")),
                                formatNullable(snapshot.hybridWeight().get("collaborative")),
                                formatNullable(snapshot.explorationRatio()),
                                joinReadable(trimToSize(globalTimeSlotGenres(context, slot.key()), 3)),
                                joinReadable(trimToSize(globalTimeSlotArtists(context, slot.key()), 3)),
                                joinReadable(trimToSize(context.topGenres(), 3)),
                                joinReadable(trimToSize(context.topArtists(), 3)),
                                joinReadable(trimToSize(context.topAlbums(), 3)),
                                joinReadable(trimToSize(context.recentAnchorAlbums(), 3)));
        }

        private String buildRankingInstruction(UserPreferenceContext context) {
                return """
                                请在固定候选歌曲中排序并返回歌单，不允许新增候选之外的歌曲。
                                JSON 必须包含 playlistTitle、playlistSubtitle、playlistReason、tags、orderedTrackIds、explanations。
                                orderedTrackIds 只能使用候选列表中真实存在的 id。
                                排序规则：
                                1. 先匹配当前时段目标和语义描述词。
                                2. 再匹配用户高频风格、歌手、近期播放、收藏和评分。
                                3. 再根据用户阶段调整探索强度：新用户更分散，稀疏用户适度探索，成熟用户更贴近历史偏好。
                                4. 最后限制同一歌手、同一专辑、同一流派过度集中。
                                个人数据：playCount30d=%d, skipRate30d=%s, feedbackCount=%d, positiveFeedback=%d, negativeFeedback=%d。
                                """.formatted(
                                context.playCount30d(),
                                formatNullable(context.skipRate30d()),
                                context.feedbackCount(),
                                context.positiveFeedbackCount(),
                                context.negativeFeedbackCount());
        }

        private String buildRankingInstruction(TimeSlotDefinition slot,
                        UserPreferenceContext context,
                        StrategySnapshot snapshot) {
                return """
                                排序规则：
                                1. 当前时段是 %s，目标是“%s”，先保证歌曲适合这个时间段。
                                2. 内容过滤信号来自“全体用户在该时段的偏好”，content=%s。
                                3. 协同过滤信号来自“当前用户自己的听歌偏好”，collaborative=%s。
                                4. 新用户或数据稀疏用户更依赖 content 与 scene，成熟用户更提高 collaborative；当前用户阶段=%s。
                                5. orderedTrackIds 只能使用候选中的真实 id，并保持同一歌手、同一专辑、同一流派不过度集中。
                                6. 标题和说明必须基于真实候选与真实偏好，不要编造不存在的歌曲或用户行为。
                                个人数据：playCount30d=%d, skipRate30d=%s, feedbackCount=%d, positiveFeedback=%d, negativeFeedback=%d。
                                """.formatted(
                                slot.label(),
                                slot.goal(),
                                formatNullable(snapshot.hybridWeight().get("content")),
                                formatNullable(snapshot.hybridWeight().get("collaborative")),
                                resolveUserStage(context),
                                context.playCount30d(),
                                formatNullable(context.skipRate30d()),
                                context.feedbackCount(),
                                context.positiveFeedbackCount(),
                                context.negativeFeedbackCount());
        }

        private String buildUserPrompt(TimeSlotDefinition slot,
                        CandidateRecallResult recall,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        StrategySnapshot snapshot,
                        int limit) {

                List<RecommendationCandidate> selectedCandidates = recall.candidates().stream()
                                .limit(Math.min(limit, 8))
                                .toList();

                String candidateBlock = selectedCandidates.stream()
                                .map(candidate -> """
                                                - id=%s | 歌名=%s | 歌手=%s | 流派=%s | 标签=%s
                                                """.formatted(
                                                candidate.musicId(),
                                                safeText(candidate.track().title()),
                                                safeText(candidate.track().artist()),
                                                safeText(candidate.track().genre()),
                                                joinReadable(trimToSize(mergeTagSeeds(candidate), 3))))
                                .collect(Collectors.joining());

                return """
                                当前歌单信息：
                                - 时段：%s
                                - 氛围主题：%s
                                - 时段目标：%s
                                - 时段说明：%s
                                - 目标歌曲数：%d

                                用户偏好摘要：
                                - 高频风格：%s
                                - 最近常听歌手：%s
                                - 最近收藏：%s
                                - 最近播放：%s

                                MCP 候选摘要：
                                - 候选数量：%d
                                - 标签趋势：%s
                                - 流派分析：%s

                                已确定歌曲列表（仅供文案参考，不需要重新选歌）：
                                %s
                                """.formatted(
                                slot.label(),
                                slot.mood(),
                                slot.goal(),
                                slot.description(),
                                limit,
                                joinReadable(trimToSize(context.topGenres(), 3)),
                                joinReadable(trimToSize(context.topArtists(), 3)),
                                joinReadable(trimToSize(userSignals.favoriteTitles(), 3)),
                                joinReadable(trimToSize(userSignals.recentTrackTitles(), 3)),
                                recall.mcpCandidateCount(),
                                joinReadable(trimToSize(recall.topTags(), 4)),
                                joinReadable(trimToSize(flattenGenreInsights(recall.genreInsights()), 3)),
                                candidateBlock);
        }

        private RefinementResult parseAiResult(TimeSlotDefinition slot,
                        List<RecommendationCandidate> candidates,
                        String rawContent,
                        int limit) {
                try {
                        String normalizedJson = normalizeJsonLikeContent(stripCodeFence(rawContent));
                        Map<String, Object> parsed = objectMapper.readValue(
                                        normalizedJson,
                                        new TypeReference<>() {
                                        });

                        List<TrackDto> fixedTracks = orderTracksByAiIds(candidates, parsed.get("orderedTrackIds"), limit);

                        List<String> tags = safeList((List<?>) parsed.get("tags")).stream()
                                        .map(String::valueOf)
                                        .map(String::trim)
                                        .filter(tag -> !tag.isBlank())
                                        .distinct()
                                        .limit(4)
                                        .toList();

                        return new RefinementResult(
                                        true,
                                        true,
                                        safeTextValue(parsed.get("playlistTitle"), slot.label() + "个性化歌单"),
                                        safeTextValue(parsed.get("playlistSubtitle"), slot.goal()),
                                        safeTextValue(parsed.get("playlistReason"), ""),
                                        tags,
                                        fixedTracks,
                            readExplanationMap(parsed.get("explanations")),
                            null);
                } catch (Exception error) {
                        log.warn("daypart ai parse failed key={} message={} raw={}",
                                        slot.key(), error.getMessage(), safeText(rawContent), error);
                        return RefinementResult.fallback("AI_PARSE_FAILED");
                }
        }

        private String normalizeJsonLikeContent(String content) {
                if (content == null || content.isBlank()) {
                        return "{}";
                }
                return content
                                .replace('\u201c', '"')
                                .replace('\u201d', '"')
                                .replace('\u2018', '\'')
                                .replace('\u2019', '\'')
                                .replace('\u3000', ' ')
                                .replace('\uFF0C', ',')
                                .replace('\uFF1A', ':')
                                .replace('\uFF1B', ';')
                                .replace('\u3001', ',')
                                .replace('\uFF08', '(')
                                .replace('\uFF09', ')')
                                .trim();
        }

        private List<TrackDto> orderTracksByAiIds(List<RecommendationCandidate> candidates, Object rawIds, int limit) {
                LinkedHashMap<Long, TrackDto> candidateMap = new LinkedHashMap<>();
                for (RecommendationCandidate candidate : safeList(candidates)) {
                        if (candidate != null && candidate.musicId() != null && candidate.track() != null) {
                                candidateMap.putIfAbsent(candidate.musicId(), candidate.track());
                        }
                }

                List<TrackDto> ordered = new ArrayList<>();
                if (rawIds instanceof List<?> ids) {
                        for (Object idValue : ids) {
                                Long id = parseLong(idValue);
                                TrackDto track = id == null ? null : candidateMap.remove(id);
                                if (track != null) {
                                        ordered.add(track);
                                }
                                if (ordered.size() >= limit) {
                                        return ordered;
                                }
                        }
                }

                for (TrackDto track : candidateMap.values()) {
                        ordered.add(track);
                        if (ordered.size() >= limit) {
                                break;
                        }
                }
                return ordered;
        }

        private Map<String, String> readExplanationMap(Object value) {
                if (!(value instanceof Map<?, ?> rawMap)) {
                        return Map.of();
                }
                Map<String, String> explanations = new LinkedHashMap<>();
                rawMap.forEach((key, rawValue) -> {
                        String text = rawValue == null ? "" : String.valueOf(rawValue).trim();
                        if (key != null && !text.isBlank()) {
                                explanations.put(String.valueOf(key), text);
                        }
                });
                return explanations;
        }

        private Long parseLong(Object value) {
                if (value == null) {
                        return null;
                }
                try {
                        return Long.parseLong(String.valueOf(value).trim());
                } catch (Exception ignored) {
                        return null;
                }
        }

        private Map<String, List<String>> buildGenreInsights(List<McpRecommendationItem> rawItems) {
                List<String> topGenres = safeList(rawItems).stream()
                                .flatMap(item -> safeList(item.genres()).stream())
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .collect(Collectors.groupingBy(
                                                value -> value,
                                                LinkedHashMap::new,
                                                Collectors.counting()))
                                .entrySet().stream()
                                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                                .limit(2)
                                .map(Map.Entry::getKey)
                                .toList();

                Map<String, List<String>> insights = new LinkedHashMap<>();
                for (String genre : topGenres) {
                        try {
                                Map<String, Object> payload = mcpClient.analyzeGenre(genre);
                                List<String> descriptors = payload.get("descriptors") instanceof List<?> list
                                                ? list.stream().map(String::valueOf).filter(value -> !value.isBlank())
                                                                .limit(3).toList()
                                                : List.of();
                                List<String> relatedGenres = payload.get("related_genres") instanceof List<?> list
                                                ? list.stream().map(String::valueOf).filter(value -> !value.isBlank())
                                                                .limit(3).toList()
                                                : List.of();
                                insights.put(genre, merge(descriptors, relatedGenres));
                        } catch (Exception ignored) {
                                insights.put(genre, List.of());
                        }
                }
                return insights;
        }

        private Map<String, List<String>> buildGenreInsightsFromCandidates(List<RecommendationCandidate> candidates) {
                List<String> topGenres = safeList(candidates).stream()
                                .flatMap(candidate -> {
                                        Set<String> merged = new LinkedHashSet<>();
                                        merged.addAll(safeList(candidate.mcpGenres()));
                                        merged.addAll(safeList(candidate.mcpSecondaryGenres()));
                                        return merged.stream();
                                })
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .collect(Collectors.groupingBy(
                                                value -> value,
                                                LinkedHashMap::new,
                                                Collectors.counting()))
                                .entrySet().stream()
                                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                                .limit(2)
                                .map(Map.Entry::getKey)
                                .toList();

                Map<String, List<String>> insights = new LinkedHashMap<>();
                for (String genre : topGenres) {
                        try {
                                Map<String, Object> payload = mcpClient.analyzeGenre(genre);
                                List<String> descriptors = payload.get("descriptors") instanceof List<?> list
                                                ? list.stream().map(String::valueOf).filter(value -> !value.isBlank())
                                                                .limit(3).toList()
                                                : List.of();
                                List<String> relatedGenres = payload.get("related_genres") instanceof List<?> list
                                                ? list.stream().map(String::valueOf).filter(value -> !value.isBlank())
                                                                .limit(3).toList()
                                                : List.of();
                                insights.put(genre, merge(descriptors, relatedGenres));
                        } catch (Exception ignored) {
                                insights.put(genre, List.of());
                        }
                }
                return insights;
        }

        private List<String> flattenGenreInsights(Map<String, List<String>> genreInsights) {
                List<String> flattened = new ArrayList<>();
                genreInsights.forEach((genre, values) -> {
                        if (values == null || values.isEmpty()) {
                                flattened.add(genre);
                                return;
                        }
                        flattened.add(genre + "：" + String.join(" / ", values));
                });
                return flattened;
        }

        private List<String> mergeTagSeeds(RecommendationCandidate candidate) {
                Set<String> merged = new LinkedHashSet<>();
                merged.addAll(safeList(candidate.mcpGenres()));
                merged.addAll(safeList(candidate.mcpSecondaryGenres()));
                merged.addAll(safeList(candidate.mcpDescriptors()));
                if (candidate.track().genre() != null && !candidate.track().genre().isBlank()) {
                        merged.add(candidate.track().genre());
                }
                return merged.stream().limit(5).toList();
        }

        private List<String> extractLyricsKeywords(TrackDto track) {
                return safeList(track.lyrics()).stream()
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .limit(3)
                                .toList();
        }

        private List<String> mergeTags(List<String> baseTags, List<String> aiTags) {
                Set<String> merged = new LinkedHashSet<>();
                merged.addAll(safeList(baseTags));
                merged.addAll(safeList(aiTags));
                return merged.stream()
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .limit(6)
                                .toList();
        }

        private List<String> merge(List<String> left, List<String> right) {
                Set<String> merged = new LinkedHashSet<>();
                merged.addAll(safeList(left));
                merged.addAll(safeList(right));
                return merged.stream().toList();
        }

        private List<String> trimToSize(List<String> values, int size) {
                List<String> safe = safeList(values).stream()
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .distinct()
                                .toList();
                return safe.size() <= size ? safe : safe.subList(0, size);
        }

        private String summarizeArgs(McpHybridRecommendationArgs args) {
                return "similarTo=" + safeList(args.similarTo())
                                + ", genres=" + safeList(args.genres())
                                + ", secondaryGenres=" + safeList(args.secondaryGenres())
                                + ", descriptors=" + safeList(args.descriptors())
                                + ", minRating=" + args.minRating()
                                + ", maxResults=" + args.maxResults()
                                + ", hybridWeight=" + args.hybridWeight();
        }

        private String summarizeUserContext(UserPreferenceContext context) {
                return "playCount30d=" + context.playCount30d()
                                + ", skipRate30d=" + formatNullable(context.skipRate30d())
                                + ", feedbackCount=" + context.feedbackCount()
                                + ", positiveFeedback=" + context.positiveFeedbackCount()
                                + ", negativeFeedback=" + context.negativeFeedbackCount()
                                + ", topGenres=" + safeList(context.topGenres())
                                + ", topArtists=" + safeList(context.topArtists())
                                + ", globalTimeSlotGenres=" + context.globalTimeSlotGenres()
                                + ", globalTimeSlotArtists=" + context.globalTimeSlotArtists();
        }

        private String defaultReason(TimeSlotDefinition slot,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        boolean fallbackUsed) {
                String preferenceSummary = !context.topGenres().isEmpty()
                                ? "最近偏好更靠近 " + String.join(" / ", context.topGenres())
                                : !userSignals.favoriteTitles().isEmpty()
                                                ? "系统参考了你近期收藏的歌曲倾向"
                                                : "系统优先根据当前时段氛围做了候选整理";
                String suffix = fallbackUsed ? "，本次已自动回退到稳定候选结果。" : "，并保留了当前时段需要的节奏与情绪。";
                return slot.description() + preferenceSummary + suffix;
        }

        private String defaultReasonLong(TimeSlotDefinition slot,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        boolean fallbackUsed) {
                String preferenceSummary = !context.topGenres().isEmpty()
                                ? "系统参考了你近期更常出现的 " + String.join(" / ", context.topGenres()) + " 倾向"
                                : !userSignals.favoriteTitles().isEmpty()
                                                ? "系统参考了你近期收藏和播放过的歌曲气质"
                                                : "系统优先采用当前时段下全体用户更常出现的风格偏好";
                String suffix = fallbackUsed
                                ? "。本次模型文案或 MCP 召回未完全命中，后端已自动回退到本地稳定候选，但仍保留了时段目标、个人画像和多样性约束。"
                                : "。歌曲顺序会尽量兼顾当下时段、你的个人画像以及不同歌手和风格之间的层次，避免整张歌单听起来过于单一。";
                return slot.description() + preferenceSummary + suffix;
        }

        private String defaultPlaylistTitle(TimeSlotDefinition slot) {
                return switch (slot.key()) {
                        case "morning" -> "晨光唤醒歌单";
                        case "afternoon" -> "午后续航歌单";
                        case "evening" -> "晚间松弛歌单";
                        case "midnight" -> "深夜低语歌单";
                        default -> slot.label() + "精选歌单";
                };
        }

        private String defaultPlaylistSubtitle(TimeSlotDefinition slot) {
                return switch (slot.key()) {
                        case "morning" -> "用清亮节奏把状态慢慢打开";
                        case "afternoon" -> "稳住专注感，也给情绪留一点空气";
                        case "evening" -> "把白天放下，留给夜晚一点柔和";
                        case "midnight" -> "低刺激、慢沉浸，适合独处时轻轻播放";
                        default -> slot.goal();
                };
        }

        private String resolveFallbackScene(TimeSlotDefinition slot, String requestedScene) {
                if (requestedScene != null && !requestedScene.isBlank()
                                && !"default".equalsIgnoreCase(requestedScene)) {
                        return requestedScene;
                }
                return switch (slot.key()) {
                        case "morning" -> "study";
                        case "afternoon" -> "default";
                        case "evening", "midnight" -> "sleep";
                        default -> "default";
                };
        }

        private String resolveFallbackEmotion(TimeSlotDefinition slot, String requestedEmotion) {
                if (requestedEmotion != null && !requestedEmotion.isBlank()
                                && !"neutral".equalsIgnoreCase(requestedEmotion)) {
                        return requestedEmotion;
                }
                return switch (slot.key()) {
                        case "morning" -> "energetic";
                        case "afternoon" -> "focus";
                        case "midnight" -> "sad";
                        default -> "neutral";
                };
        }

        private List<String> globalTimeSlotGenres(UserPreferenceContext context, String timeSlot) {
                return safeMapList(context.globalTimeSlotGenres(), timeSlot);
        }

        private List<String> globalTimeSlotArtists(UserPreferenceContext context, String timeSlot) {
                return safeMapList(context.globalTimeSlotArtists(), timeSlot);
        }

        private List<String> safeMapList(Map<String, List<String>> values, String key) {
                if (values == null || key == null) {
                        return List.of();
                }
                return safeList(values.get(key.trim().toLowerCase(Locale.ROOT)));
        }

        private String resolveUserStage(UserPreferenceContext context) {
                if (context.playCount30d() == 0) {
                        return "NEW_USER";
                }
                if (context.playCount30d() < 30) {
                        return "SPARSE_USER";
                }
                if (context.playCount30d() < 80) {
                        return "GROWING_USER";
                }
                return "MATURE_USER";
        }

        private String joinReadable(List<String> values) {
                List<String> safe = safeList(values).stream()
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .toList();
                return safe.isEmpty() ? "暂无" : String.join(" / ", safe);
        }

        private String safeText(String value) {
                return value == null || value.isBlank() ? "暂无" : value.trim();
        }

        private String normalizeBucket(String value) {
                return value == null || value.isBlank() ? "-" : value.trim().toLowerCase(Locale.ROOT);
        }

        private String safeTextValue(Object value, String fallback) {
                if (value == null) {
                        return fallback;
                }
                String text = String.valueOf(value).trim();
                return text.isBlank() ? fallback : text;
        }

        private String firstNonBlank(String value, String fallback) {
                return value == null || value.isBlank() ? fallback : value;
        }

        private String formatNullable(Double value) {
                return value == null ? "暂无" : String.format(Locale.ROOT, "%.4f", value);
        }

        private String stripCodeFence(String content) {
                if (content == null) {
                        return "";
                }
                String normalized = content.trim();
                if (normalized.startsWith("```")) {
                        normalized = normalized.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
                }
                return normalized;
        }

        private <T> List<T> safeList(List<T> value) {
                return value == null ? List.of() : value;
        }

        public record TimeSlotPlaylistBuildResult(
                        TimeSlotPlaylistDto playlist,
                        boolean aiAttempted,
                        boolean aiSuccess,
                        int mcpCandidateCount,
                        int aiFinalCount,
                        String fallbackReason) {
        }

        private record TimeSlotDefinition(
                        String key,
                        String label,
                        String mood,
                        String description,
                        String goal,
                        List<String> tags) {
        }

        private record CandidateRecallResult(
                        List<RecommendationCandidate> candidates,
                        int mcpCandidateCount,
                        List<McpRecommendationItem> rawItems,
                        Map<String, List<String>> genreInsights) {
                boolean hasMcpCandidates() {
                        return mcpCandidateCount > 0 && candidates != null && !candidates.isEmpty();
                }

                List<String> topTags() {
                        return rawItems.stream()
                                        .flatMap(item -> mergeRawTags(item).stream())
                                        .filter(Objects::nonNull)
                                        .map(String::trim)
                                        .filter(value -> !value.isBlank())
                                        .collect(Collectors.groupingBy(
                                                        value -> value,
                                                        LinkedHashMap::new,
                                                        Collectors.counting()))
                                        .entrySet().stream()
                                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                                        .limit(5)
                                        .map(Map.Entry::getKey)
                                        .toList();
                }

                private List<String> mergeRawTags(McpRecommendationItem item) {
                        Set<String> merged = new LinkedHashSet<>();
                        if (item.genres() != null) {
                                merged.addAll(item.genres());
                        }
                        if (item.secondaryGenres() != null) {
                                merged.addAll(item.secondaryGenres());
                        }
                        if (item.descriptors() != null) {
                                merged.addAll(item.descriptors());
                        }
                        return merged.stream().toList();
                }
        }

        private record RefinementResult(
                        boolean aiAttempted,
                        boolean aiSuccess,
                        String playlistTitle,
                        String playlistSubtitle,
                        String playlistReason,
                        List<String> tags,
                        List<TrackDto> tracks,
                        Map<String, String> explanations,
                        String fallbackReason) {
                static RefinementResult fallback(String fallbackReason) {
                        return new RefinementResult(
                                        true,
                                        false,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        List.of(),
                                        Map.of(),
                                        fallbackReason);
                }
        }

        private record UserSignalBundle(
                        List<HistoryItemDto> recentHistory,
                        List<FavoriteTrackDto> favorites) {
                List<String> recentTrackTitles() {
                        return recentHistory == null ? List.of()
                                        : recentHistory.stream()
                                                        .map(HistoryItemDto::title)
                                                        .filter(Objects::nonNull)
                                                        .map(String::trim)
                                                        .filter(value -> !value.isBlank())
                                                        .distinct()
                                                        .limit(5)
                                                        .toList();
                }

                List<String> favoriteTitles() {
                        return favorites == null ? List.of()
                                        : favorites.stream()
                                                        .map(FavoriteTrackDto::title)
                                                        .filter(Objects::nonNull)
                                                        .map(String::trim)
                                                        .filter(value -> !value.isBlank())
                                                        .distinct()
                                                        .limit(5)
                                                        .toList();
                }
        }

        private record RecommendationRecallBundle(
                        List<RecommendationCandidate> candidates,
                        List<McpRecommendationItem> rawItems) {
        }
}
