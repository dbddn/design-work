package com.music.reco.recommendation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.assistant.dto.AiCompletionResult;
import com.music.reco.assistant.dto.AiPromptMessage;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.mcp.client.McpClient;
import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.mcp.dto.McpRecommendationItem;
import com.music.reco.mcp.dto.McpRecommendationPayload;
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
        private final OllamaDaypartLlmService ollamaDaypartLlmService;
        private final ObjectMapper objectMapper;

        public TimeSlotPlaylistOrchestratorService(LegacyJdbcRepository legacyJdbcRepository,
                        McpClient mcpClient,
                        McpQueryMappingService mcpQueryMappingService,
                        McpCandidateToTrackMatcher mcpCandidateToTrackMatcher,
                        OllamaDaypartLlmService ollamaDaypartLlmService,
                        ObjectMapper objectMapper) {
                this.legacyJdbcRepository = legacyJdbcRepository;
                this.mcpClient = mcpClient;
                this.mcpQueryMappingService = mcpQueryMappingService;
                this.mcpCandidateToTrackMatcher = mcpCandidateToTrackMatcher;
                this.ollamaDaypartLlmService = ollamaDaypartLlmService;
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

                return TIME_SLOTS.stream()
                                .map(slot -> buildSinglePlaylist(requestId, userId, slot, scene, emotion, limit, snapshot, context,
                                                userSignals))
                                .toList();
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

                return TIME_SLOTS.stream()
                                .map(slot -> buildSinglePlaylistMcpOnly(requestId, userId, slot, scene, emotion, limit,
                                                snapshot, context, userSignals))
                                .toList();
        }

        private TimeSlotPlaylistBuildResult buildSinglePlaylist(String requestId,
                        String userId,
                        TimeSlotDefinition slot,
                        String scene,
                        String emotion,
                        int limit,
                        StrategySnapshot snapshot,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals) {
                try {
                        CandidateRecallResult recall = recallCandidates(requestId, userId, slot, scene, emotion, limit, snapshot,
                                        context);
                        RefinementResult refinement = refinePlaylist(requestId, slot, recall, context, userSignals, limit);
                        List<TrackDto> directRecallTracks = recall.candidates().stream()
                                        .map(RecommendationCandidate::track)
                                        .limit(limit)
                                        .toList();

                        List<TrackDto> finalTracks = refinement.tracks().isEmpty()
                                        ? directRecallTracks
                                        : refinement.tracks();

                        String fallbackReason = refinement.fallbackReason();
                        boolean fallbackUsed = fallbackReason != null && !fallbackReason.isBlank();
                        TimeSlotPlaylistDto playlist = new TimeSlotPlaylistDto(
                                        slot.key(),
                                        slot.label(),
                                        slot.mood(),
                                        slot.description(),
                                        firstNonBlank(refinement.playlistTitle(), slot.label() + "个性化歌单"),
                                        firstNonBlank(refinement.playlistSubtitle(), slot.goal()),
                                        firstNonBlank(refinement.playlistReason(),
                                                        defaultReason(slot, context, userSignals, fallbackUsed)),
                                        mergeTags(slot.tags(), refinement.tags()),
                                        finalTracks,
                                        refinement.explanations(),
                                        ollamaDaypartLlmService.isEnabled(),
                                        refinement.aiSuccess(),
                                        fallbackUsed,
                                        fallbackReason,
                                        recall.mcpCandidateCount(),
                                        finalTracks.size());

                        log.info("daypart slot built requestId={} key={} tracks={} mcpCandidates={} aiAttempted={} aiSuccess={} fallbackReason={}",
                                        requestId, slot.key(), finalTracks.size(), recall.mcpCandidateCount(),
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
                                        .limit(limit)
                                        .toList();
                        TimeSlotPlaylistDto playlist = new TimeSlotPlaylistDto(
                                        slot.key(),
                                        slot.label(),
                                        slot.mood(),
                                        slot.description(),
                                        slot.label() + "涓€у寲姝屽崟",
                                        slot.goal(),
                                        defaultReason(slot, context, userSignals, true),
                                        slot.tags(),
                                        fallbackTracks,
                                        Map.of(),
                                        ollamaDaypartLlmService.isEnabled(),
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
                        UserSignalBundle userSignals) {
                CandidateRecallResult recall = recallCandidates(requestId, userId, slot, scene, emotion, limit, snapshot,
                                context);
                List<TrackDto> finalTracks = recall.candidates().stream()
                                .map(RecommendationCandidate::track)
                                .limit(limit)
                                .toList();
                boolean usedLocalFallback = !recall.hasMcpCandidates();
                String fallbackReason = usedLocalFallback
                                ? "NO_MCP_CANDIDATES_USE_LOCAL"
                                : "PERSISTED_PLAYLIST_PENDING_USE_MCP";

                TimeSlotPlaylistDto playlist = new TimeSlotPlaylistDto(
                                slot.key(),
                                slot.label(),
                                slot.mood(),
                                slot.description(),
                                slot.label() + "个性化歌单",
                                slot.goal(),
                                defaultReason(slot, context, userSignals, usedLocalFallback),
                                slot.tags(),
                                finalTracks,
                                Map.of(),
                                ollamaDaypartLlmService.isEnabled(),
                                false,
                                usedLocalFallback,
                                fallbackReason,
                                recall.mcpCandidateCount(),
                                finalTracks.size());

                log.info("daypart fast slot built requestId={} key={} tracks={} mcpCandidates={} fallbackReason={}",
                                requestId, slot.key(), finalTracks.size(), recall.mcpCandidateCount(), fallbackReason);

                return new TimeSlotPlaylistBuildResult(
                                playlist,
                                false,
                                false,
                                recall.mcpCandidateCount(),
                                finalTracks.size(),
                                fallbackReason);
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
                log.info("daypart mcp primary query requestId={} key={} args={}", requestId, slot.key(), summarizeArgs(primaryArgs));
                RecommendationRecallBundle recommendationBundle = recallByRecommendations(requestId, slot.key(), primaryArgs,
                                candidateLimit);
                if (!recommendationBundle.candidates().isEmpty()) {
                        List<RecommendationCandidate> finalCandidates = recommendationBundle.candidates().stream()
                                        .limit(candidateLimit)
                                        .toList();
                        List<McpRecommendationItem> rawItems = recommendationBundle.rawItems();
                        Map<String, List<String>> insights = rawItems.isEmpty()
                                        ? buildGenreInsightsFromCandidates(finalCandidates)
                                        : buildGenreInsights(rawItems);
                        log.info("daypart mcp recall merged requestId={} key={} source=get_recommendations candidates={} rawItems={}",
                                        requestId, slot.key(), finalCandidates.size(), rawItems.size());
                        return new CandidateRecallResult(
                                        finalCandidates,
                                        finalCandidates.size(),
                                        rawItems,
                                        insights);
                }

                List<RecommendationCandidate> localFallback = localFallbackCandidates(userId, slot, scene, emotion,
                                candidateLimit, context);
                log.info("daypart local fallback requestId={} key={} candidates={}", requestId, slot.key(), localFallback.size());
                return new CandidateRecallResult(localFallback, 0, List.of(), Map.of());
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

        private RefinementResult refinePlaylist(String requestId,
                        TimeSlotDefinition slot,
                        CandidateRecallResult recall,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
                        int limit) {
                if (recall.candidates().isEmpty()) {
                        return RefinementResult.fallback("NO_CANDIDATES");
                }
                if (!recall.hasMcpCandidates()) {
                        log.info("daypart skip ai requestId={} key={} reason=NO_MCP_CANDIDATES candidateCount={}",
                                        requestId, slot.key(), recall.candidates().size());
                        return RefinementResult.fallback("NO_MCP_CANDIDATES_USE_LOCAL");
                }
                if (!ollamaDaypartLlmService.isEnabled()) {
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
                        AiCompletionResult completion = ollamaDaypartLlmService.complete(
                                        requestId,
                                        slot.key(),
                                        buildPromptMessages(slot, recall, context, userSignals, limit));
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
                                                buildUserPrompt(slot, recall, context, userSignals, limit)));
        }

        private String buildUserPrompt(TimeSlotDefinition slot,
                        CandidateRecallResult recall,
                        UserPreferenceContext context,
                        UserSignalBundle userSignals,
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

                        List<TrackDto> fixedTracks = safeList(candidates).stream()
                                        .map(RecommendationCandidate::track)
                                        .limit(limit)
                                        .toList();

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
                                Map.of(),
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
