package com.music.reco.recommendation.service;

import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.mcp.client.McpClient;
import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.mcp.dto.McpRecommendationItem;
import com.music.reco.mcp.dto.McpRecommendationPayload;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.recommendation.dto.DaypartRecommendationResponse;
import com.music.reco.recommendation.dto.McpRecommendationDebugMatchDto;
import com.music.reco.recommendation.dto.McpRecommendationDebugResponse;
import com.music.reco.recommendation.dto.RecommendationCandidate;
import com.music.reco.recommendation.dto.RecommendationFeedbackRequest;
import com.music.reco.recommendation.dto.RecommendationOnboardingRequest;
import com.music.reco.recommendation.dto.RecommendationResponse;
import com.music.reco.recommendation.dto.StrategySnapshotResponse;
import com.music.reco.recommendation.dto.TimeSlotPlaylistDto;
import com.music.reco.recommendation.dto.UserPreferenceContext;
import com.music.reco.recommendation.strategy.HybridWeightStrategyService;
import com.music.reco.recommendation.strategy.StrategySnapshot;
import com.music.reco.user.dto.UserStatsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RecommendationService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final String STRATEGY_VERSION = "v2";
    private static final int ONBOARDING_OPTION_LIMIT = 10;
    private static final int SPARSE_USER_THRESHOLD = 30;
    private static final int MATURE_USER_THRESHOLD = 80;

    private final HybridWeightStrategyService strategyService;
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final McpClient mcpClient;
    private final McpQueryMappingService mcpQueryMappingService;
    private final McpCandidateToTrackMatcher mcpCandidateToTrackMatcher;
    private final TimeSlotPlaylistOrchestratorService timeSlotPlaylistOrchestratorService;
    private final DaypartPlaylistSnapshotService daypartPlaylistSnapshotService;
    private final DaypartPlaylistGenerationCoordinator daypartPlaylistGenerationCoordinator;

    public RecommendationService(HybridWeightStrategyService strategyService,
                                 LegacyJdbcRepository legacyJdbcRepository,
                                 McpClient mcpClient,
                                 McpQueryMappingService mcpQueryMappingService,
                                 McpCandidateToTrackMatcher mcpCandidateToTrackMatcher,
                                 TimeSlotPlaylistOrchestratorService timeSlotPlaylistOrchestratorService,
                                 DaypartPlaylistSnapshotService daypartPlaylistSnapshotService,
                                 DaypartPlaylistGenerationCoordinator daypartPlaylistGenerationCoordinator) {
        this.strategyService = strategyService;
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.mcpClient = mcpClient;
        this.mcpQueryMappingService = mcpQueryMappingService;
        this.mcpCandidateToTrackMatcher = mcpCandidateToTrackMatcher;
        this.timeSlotPlaylistOrchestratorService = timeSlotPlaylistOrchestratorService;
        this.daypartPlaylistSnapshotService = daypartPlaylistSnapshotService;
        this.daypartPlaylistGenerationCoordinator = daypartPlaylistGenerationCoordinator;
    }

    public RecommendationResponse recommend(String userId, String scene, String emotion, int limit) {
        if (isGuestUser(userId)) {
            return lockedGuestResponse();
        }

        long startedAt = System.currentTimeMillis();
        UserStatsResponse stats = legacyJdbcRepository.buildUserStats(userId);
        List<String> selectedGenres = legacyJdbcRepository.findOnboardingGenres(userId);
        List<String> onboardingOptions = legacyJdbcRepository.onboardingGenreOptions(ONBOARDING_OPTION_LIMIT);

        if (stats.playCount30d() == 0 && selectedGenres.isEmpty()) {
            return onboardingResponse(
                    userId,
                    stats,
                    onboardingOptions,
                    List.of(),
                    "先告诉我你偏爱的风格，我就能为你建立第一版听歌画像。"
            );
        }

        StrategySnapshot snapshot = strategyService.generate(userId, scene, emotion, stats.playCount30d(), stats.skipRate30d());
        UserPreferenceContext context = buildUserPreferenceContext(userId, stats, selectedGenres);
        List<TrackDto> tracks = resolveRecommendationTracks(userId, scene, emotion, limit, stats, snapshot, context);
        String requestId = "local-" + startedAt;

        legacyJdbcRepository.insertRecommendationLog(
                userId,
                requestId,
                scene,
                emotion,
                snapshot.coldStart(),
                snapshot.explorationRatio(),
                snapshot.hybridWeight(),
                tracks,
                STRATEGY_VERSION,
                Math.toIntExact(Math.max(0, System.currentTimeMillis() - startedAt)),
                null,
                false,
                false,
                0,
                tracks.size(),
                null
        );

        return new RecommendationResponse(
                requestId,
                STRATEGY_VERSION,
                snapshot.hybridWeight(),
                tracks,
                resolveUserStage(stats),
                false,
                false,
                selectedGenres,
                onboardingOptions,
                stats.playCount30d(),
                buildSummary(stats, selectedGenres, tracks.isEmpty())
        );
    }

    public DaypartRecommendationResponse recommendDaypartPlaylists(String userId, String scene, String emotion, int limit) {
        if (isGuestUser(userId)) {
            return new DaypartRecommendationResponse(
                    "guest-dayparts",
                    STRATEGY_VERSION,
                    "VISITOR",
                    true,
                    false,
                    List.of(),
                    legacyJdbcRepository.onboardingGenreOptions(ONBOARDING_OPTION_LIMIT),
                    0,
                    "登录后可查看结合时段场景与个人偏好的专属歌单。",
                    List.of()
            );
        }

        long startedAt = System.currentTimeMillis();
        String requestId = "daypart-" + startedAt;
        log.info("daypart request start requestId={} userId={} scene={} emotion={} limit={}",
                requestId, userId, scene, emotion, limit);
        UserStatsResponse stats = legacyJdbcRepository.buildUserStats(userId);
        List<String> selectedGenres = legacyJdbcRepository.findOnboardingGenres(userId);
        List<String> onboardingOptions = legacyJdbcRepository.onboardingGenreOptions(ONBOARDING_OPTION_LIMIT);

        if (stats.playCount30d() == 0 && selectedGenres.isEmpty()) {
            return new DaypartRecommendationResponse(
                    requestId,
                    STRATEGY_VERSION,
                    "NEW_USER",
                    false,
                    true,
                    selectedGenres,
                    onboardingOptions,
                    stats.playCount30d(),
                    "先完成新手引导，我会根据你选择的风格为四个时段生成首版歌单。",
                    List.of()
            );
        }

        StrategySnapshot snapshot = strategyService.generate(userId, scene, emotion, stats.playCount30d(), stats.skipRate30d());
        List<TimeSlotPlaylistDto> storedPlaylists = daypartPlaylistSnapshotService.loadStoredPlaylists(userId, scene, emotion, limit);
        boolean hasCompleteStored = daypartPlaylistSnapshotService.hasCompleteStoredPlaylists(userId, scene, emotion, limit);

        List<TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult> buildResults;
        if (hasCompleteStored) {
            buildResults = storedPlaylists.stream()
                    .map(playlist -> new TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult(
                            playlist,
                            playlist.aiEnabled(),
                            playlist.aiSuccess(),
                            playlist.candidateCount(),
                            playlist.finalCount(),
                            playlist.fallbackReason()))
                    .toList();
            log.info("daypart request hit stored snapshot requestId={} playlistCount={}",
                    requestId, buildResults.size());
        } else {
            daypartPlaylistGenerationCoordinator.enqueue(userId, scene, emotion, limit, "api-request");
            buildResults = daypartPlaylistSnapshotService.buildFastView(requestId, userId, scene, emotion, limit);
            log.info("daypart request using fast MCP view requestId={} queued={} playlistCount={}",
                    requestId,
                    daypartPlaylistGenerationCoordinator.isQueued(userId, scene, emotion),
                    buildResults.size());
        }

        int latencyMs = Math.toIntExact(Math.max(0, System.currentTimeMillis() - startedAt));
        log.info("daypart request built requestId={} latencyMs={} playlistCount={}",
                requestId, latencyMs, buildResults.size());

        for (TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult buildResult : buildResults) {
            TimeSlotPlaylistDto playlist = buildResult.playlist();
            legacyJdbcRepository.insertRecommendationLog(
                    userId,
                    requestId,
                    scene,
                    emotion,
                    snapshot.coldStart(),
                    snapshot.explorationRatio(),
                    snapshot.hybridWeight(),
                    playlist.tracks(),
                    STRATEGY_VERSION,
                    latencyMs,
                    playlist.key(),
                    buildResult.aiAttempted(),
                    buildResult.aiSuccess(),
                    buildResult.mcpCandidateCount(),
                    buildResult.aiFinalCount(),
                    buildResult.fallbackReason()
            );
        }

        return new DaypartRecommendationResponse(
                requestId,
                STRATEGY_VERSION,
                resolveUserStage(stats),
                false,
                false,
                selectedGenres,
                onboardingOptions,
                stats.playCount30d(),
                buildDaypartSummary(stats, selectedGenres, buildResults),
                buildResults.stream().map(TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult::playlist).toList()
        );
    }

    public RecommendationResponse saveOnboardingPreferences(String userId, RecommendationOnboardingRequest request) {
        if (isGuestUser(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "访客模式下不能保存初始偏好");
        }

        List<String> genres = request.genres() == null ? List.of() : request.genres().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(6)
                .toList();
        if (genres.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个偏好标签");
        }

        legacyJdbcRepository.saveOnboardingGenres(userId, genres);
        if (request.gender() != null && !request.gender().isBlank()) {
            legacyJdbcRepository.updateUserGender(userId, request.gender().trim());
        }
        return recommend(userId, "default", "neutral", 20);
    }

    public StrategySnapshotResponse strategySnapshot(String userId) {
        UserStatsResponse stats = legacyJdbcRepository.buildUserStats(userId);
        StrategySnapshot snapshot = strategyService.generate(userId, "default", "neutral", stats.playCount30d(), stats.skipRate30d());
        return new StrategySnapshotResponse(snapshot.coldStart(), snapshot.hybridWeight(), snapshot.explorationRatio());
    }

    public void feedback(String userId, RecommendationFeedbackRequest request) {
        legacyJdbcRepository.insertRecommendationFeedback(
                userId,
                request.trackId(),
                request.rating(),
                request.liked(),
                request.skipped(),
                "recommendation"
        );
    }

    public StrategySnapshotResponse recalculate(String userId) {
        return strategySnapshot(userId);
    }

    public McpRecommendationDebugResponse debugMcpRecommendation(String userId, String scene, String emotion, int limit) {
        UserStatsResponse stats = legacyJdbcRepository.buildUserStats(userId);
        List<String> selectedGenres = legacyJdbcRepository.findOnboardingGenres(userId);
        StrategySnapshot snapshot = strategyService.generate(userId, scene, emotion, stats.playCount30d(), stats.skipRate30d());
        UserPreferenceContext context = buildUserPreferenceContext(userId, stats, selectedGenres);
        McpHybridRecommendationArgs args = mcpQueryMappingService.buildRecommendationArgs(
                scene,
                emotion,
                Math.max(limit, 10),
                snapshot,
                context
        );

        try {
            McpRecommendationPayload payload = mcpClient.getRecommendations(args);
            List<McpRecommendationItem> rawItems = payload.recommendations() == null ? List.of() : payload.recommendations();

            List<McpRecommendationDebugMatchDto> matches = rawItems.stream()
                    .map(item -> toDebugMatch(item, mcpCandidateToTrackMatcher.matchRecommendation(item)))
                    .toList();

            int matchedCount = (int) matches.stream().filter(McpRecommendationDebugMatchDto::matched).count();
            List<TrackDto> fallbackTracks = matchedCount == 0
                    ? localFallbackTracks(userId, scene, emotion, limit, context)
                    : List.of();

            return new McpRecommendationDebugResponse(
                    userId,
                    scene,
                    emotion,
                    limit,
                    snapshot.coldStart(),
                    snapshot.explorationRatio(),
                    args,
                    payload,
                    rawItems.size(),
                    matchedCount,
                    Math.max(0, rawItems.size() - matchedCount),
                    !fallbackTracks.isEmpty(),
                    null,
                    matches,
                    fallbackTracks
            );
        } catch (Exception error) {
            List<TrackDto> fallbackTracks = localFallbackTracks(userId, scene, emotion, limit, context);
            return new McpRecommendationDebugResponse(
                    userId,
                    scene,
                    emotion,
                    limit,
                    snapshot.coldStart(),
                    snapshot.explorationRatio(),
                    args,
                    null,
                    0,
                    0,
                    0,
                    true,
                    error.getMessage(),
                    List.of(),
                    fallbackTracks
            );
        }
    }

    private List<TrackDto> resolveRecommendationTracks(String userId,
                                                       String scene,
                                                       String emotion,
                                                       int limit,
                                                       UserStatsResponse stats,
                                                       StrategySnapshot snapshot,
                                                       UserPreferenceContext context) {
        if (stats.playCount30d() == 0 && !context.topGenres().isEmpty()) {
            List<TrackDto> onboardingTracks = legacyJdbcRepository.recommendTracksByGenres(context.topGenres(), limit);
            if (!onboardingTracks.isEmpty()) {
                return onboardingTracks;
            }
        }
        return recommendWithMcpFirst(userId, scene, emotion, limit, snapshot, context);
    }

    private List<TrackDto> recommendWithMcpFirst(String userId,
                                                 String scene,
                                                 String emotion,
                                                 int limit,
                                                 StrategySnapshot snapshot,
                                                 UserPreferenceContext context) {
        try {
            McpHybridRecommendationArgs args = mcpQueryMappingService.buildRecommendationArgs(
                    scene,
                    emotion,
                    Math.max(limit, 10),
                    snapshot,
                    context
            );
            McpRecommendationPayload payload = mcpClient.getRecommendations(args);
            List<TrackDto> matchedTracks = payload.recommendations() == null ? List.of() : payload.recommendations().stream()
                    .map(mcpCandidateToTrackMatcher::matchRecommendation)
                    .flatMap(Optional::stream)
                    .map(RecommendationCandidate::track)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toMap(
                                    TrackDto::id,
                                    track -> track,
                                    (left, right) -> left,
                                    LinkedHashMap::new
                            ),
                            map -> map.values().stream().limit(limit).toList()
                    ));

            if (!matchedTracks.isEmpty()) {
                return matchedTracks;
            }
        } catch (Exception ignored) {
            // fall back to local recommendation pipeline
        }

        return localFallbackTracks(userId, scene, emotion, limit, context);
    }

    private List<TrackDto> localFallbackTracks(String userId,
                                               String scene,
                                               String emotion,
                                               int limit,
                                               UserPreferenceContext context) {
        if (!context.topGenres().isEmpty()) {
            List<TrackDto> byGenre = legacyJdbcRepository.recommendTracksByGenres(context.topGenres(), limit);
            if (!byGenre.isEmpty()) {
                return byGenre;
            }
        }
        return legacyJdbcRepository.recommendTracks(scene, emotion, limit);
    }

    private UserPreferenceContext buildUserPreferenceContext(String userId,
                                                             UserStatsResponse stats,
                                                             List<String> onboardingGenres) {
        List<String> userTopGenres = legacyJdbcRepository.findUserTopGenreLabels(userId, 3);
        List<String> effectiveGenres = userTopGenres.isEmpty() ? onboardingGenres : userTopGenres;
        return new UserPreferenceContext(
                effectiveGenres,
                legacyJdbcRepository.findUserTopArtistNames(userId, 3),
                legacyJdbcRepository.findUserTopAlbums(userId, 3),
                legacyJdbcRepository.findRecentAnchorAlbums(userId, 3),
                stats.playCount30d(),
                stats.skipRate30d(),
                stats.playCount30d() < SPARSE_USER_THRESHOLD
        );
    }

    private RecommendationResponse lockedGuestResponse() {
        return new RecommendationResponse(
                "guest-locked",
                STRATEGY_VERSION,
                Map.of("popular", 1.0),
                List.of(),
                "VISITOR",
                true,
                false,
                List.of(),
                legacyJdbcRepository.onboardingGenreOptions(ONBOARDING_OPTION_LIMIT),
                0,
                "登录后可查看专属推荐、保存偏好，并持续积累你的听歌画像。"
        );
    }

    private RecommendationResponse onboardingResponse(String userId,
                                                      UserStatsResponse stats,
                                                      List<String> onboardingOptions,
                                                      List<String> selectedGenres,
                                                      String summary) {
        return new RecommendationResponse(
                userId + "-onboarding",
                STRATEGY_VERSION,
                Map.of(
                        "popular", 0.30,
                        "content", 0.48,
                        "scene", 0.17,
                        "collaborative", 0.05
                ),
                List.of(),
                "NEW_USER",
                false,
                true,
                selectedGenres,
                onboardingOptions,
                stats.playCount30d(),
                summary
        );
    }

    private String resolveUserStage(UserStatsResponse stats) {
        if (stats.playCount30d() == 0) {
            return "NEW_USER";
        }
        if (stats.playCount30d() < MATURE_USER_THRESHOLD) {
            return "SPARSE_USER";
        }
        return "MATURE_USER";
    }

    private String buildSummary(UserStatsResponse stats, List<String> selectedGenres, boolean emptyResult) {
        if (emptyResult) {
            return "当前没有命中更合适的推荐结果，系统已回退到本地偏好兜底。";
        }
        if (stats.playCount30d() == 0 && !selectedGenres.isEmpty()) {
            return "已根据你的初始偏好建立第一版画像，后续会随着听歌记录逐步提升协同过滤占比。";
        }
        if (stats.playCount30d() < SPARSE_USER_THRESHOLD) {
            return "你的听歌数据还在积累阶段，当前会优先放大内容过滤与风格匹配。";
        }
        if (stats.playCount30d() < MATURE_USER_THRESHOLD) {
            return "系统正在平衡内容理解和协同过滤，推荐会开始更贴近相似用户的行为。";
        }
        return "你的画像已经比较稳定，当前推荐会更偏向协同过滤和长期偏好。";
    }

    private String buildDaypartSummary(UserStatsResponse stats,
                                       List<String> selectedGenres,
                                       List<TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult> buildResults) {
        long aiSuccessCount = buildResults.stream().filter(TimeSlotPlaylistOrchestratorService.TimeSlotPlaylistBuildResult::aiSuccess).count();
        if (stats.playCount30d() == 0 && !selectedGenres.isEmpty()) {
            return "";
        }
        if (aiSuccessCount == 0) {
            return "";
        }
        if (aiSuccessCount < buildResults.size()) {
            return "";
        }
        if (stats.playCount30d() < SPARSE_USER_THRESHOLD) {
            return "";
        }
        return "";
    }

    private boolean isGuestUser(String userId) {
        return userId == null || userId.isBlank() || userId.startsWith("guest");
    }

    private McpRecommendationDebugMatchDto toDebugMatch(McpRecommendationItem item,
                                                        Optional<RecommendationCandidate> candidateOptional) {
        if (candidateOptional.isPresent()) {
            RecommendationCandidate candidate = candidateOptional.get();
            TrackDto track = candidate.track();
            return new McpRecommendationDebugMatchDto(
                    item.artist(),
                    item.album(),
                    item.explanation(),
                    item.similarityScore(),
                    item.metadataScore(),
                    item.combinedScore(),
                    track.id(),
                    track.title(),
                    track.artist(),
                    track.album(),
                    candidate.recallSource(),
                    true
            );
        }

        return new McpRecommendationDebugMatchDto(
                item.artist(),
                item.album(),
                item.explanation(),
                item.similarityScore(),
                item.metadataScore(),
                item.combinedScore(),
                null,
                null,
                null,
                null,
                "unmatched",
                false
        );
    }
}
