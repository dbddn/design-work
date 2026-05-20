package com.music.reco.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.assistant.dto.AiCompletionResult;
import com.music.reco.assistant.dto.AiPromptMessage;
import com.music.reco.assistant.dto.AssistantChatRequest;
import com.music.reco.assistant.dto.AssistantChatResponse;
import com.music.reco.assistant.dto.AssistantMessageDto;
import com.music.reco.assistant.dto.AssistantPlaylistTrackDto;
import com.music.reco.config.AssistantModelProperties;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.HistoryItemDto;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.recommendation.dto.RecommendationResponse;
import com.music.reco.recommendation.service.RecommendationService;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AssistantService {
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final int CANDIDATE_LIMIT = 12;
    private static final int MIN_PLAYLIST_SIZE = 4;
    private static final int MAX_PLAYLIST_SIZE = 8;

    private final AssistantModelProperties properties;
    private final ObjectMapper objectMapper;
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final RecommendationService recommendationService;
    private final AssistantLlmClientService assistantLlmClientService;

    public AssistantService(AssistantModelProperties properties,
                            ObjectMapper objectMapper,
                            LegacyJdbcRepository legacyJdbcRepository,
                            RecommendationService recommendationService,
                            AssistantLlmClientService assistantLlmClientService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.recommendationService = recommendationService;
        this.assistantLlmClientService = assistantLlmClientService;
    }

    public AssistantChatResponse chat(String userId, AssistantChatRequest request) {
        String scene = normalize(request.scene(), "default");
        String emotion = normalize(request.emotion(), "neutral");
        String style = normalize(request.style(), "");
        UserStatsResponse stats = isGuest(userId) ? new UserStatsResponse(0, 0, 0, 0) : legacyJdbcRepository.buildUserStats(userId);
        UserProfileResponse profile = isGuest(userId)
                ? null
                : legacyJdbcRepository.findUserProfile(userId).orElse(null);
        List<String> topGenres = isGuest(userId)
                ? List.of()
                : safeList(legacyJdbcRepository.findUserTopGenreLabels(userId, 5));
        List<HistoryItemDto> history = isGuest(userId)
                ? List.of()
                : safeList(legacyJdbcRepository.history(userId, 8));
        List<Map<String, Object>> feedbackSignals = isGuest(userId)
                ? List.of()
                : safeList(legacyJdbcRepository.findRecentFeedbackSignals(userId, 8));
        List<TrackDto> candidateTracks = resolveCandidates(userId, scene, emotion);
        log.info("assistant request userId={} scene={} emotion={} style={} input={} candidateCount={}",
                userId, scene, emotion, blankToDefault(style, "none"), truncate(request.message(), 160), candidateTracks.size());

        try {
            AiCompletionResult llmReply = assistantLlmClientService.complete(
                    buildPromptMessages(request, profile, stats, topGenres, history, feedbackSignals, candidateTracks)
            );
            ParsedAssistantReply parsed = parseAssistantReply(
                    llmReply.content(),
                    candidateTracks,
                    request.message(),
                    llmReply.reasoningSummary(),
                    false
            );
            AssistantChatResponse response = toResponse(parsed);
            log.info("assistant response userId={} candidateCount={} llmSuccess=true fallback={} finalSongCount={}",
                    userId, candidateTracks.size(), response.fallback(), response.songs().size());
            return response;
        } catch (Exception error) {
            ParsedAssistantReply parsed = fallbackReply(
                    candidateTracks,
                    "当前 AI 助手暂时不可用，我先根据系统候选结果为你整理了一份本地推荐歌单。",
                    request.message(),
                    List.of("本次已自动回退到系统候选池，方便你继续试听和挑选。")
            );
            AssistantChatResponse response = toResponse(parsed);
            log.warn("assistant response userId={} candidateCount={} llmSuccess=false fallback=true finalSongCount={} error={}",
                    userId, candidateTracks.size(), response.songs().size(), error.getMessage());
            return response;
        }
    }

    private List<TrackDto> resolveCandidates(String userId, String scene, String emotion) {
        if (!isGuest(userId)) {
            RecommendationResponse response = recommendationService.recommend(userId, scene, emotion, CANDIDATE_LIMIT);
            if (!response.onboardingRequired() && !response.guest() && !safeList(response.tracks()).isEmpty()) {
                return response.tracks().stream().limit(CANDIDATE_LIMIT).toList();
            }
        }
        return safeList(legacyJdbcRepository.recommendTracks(scene, emotion, CANDIDATE_LIMIT));
    }

    private List<AiPromptMessage> buildPromptMessages(AssistantChatRequest request,
                                                      UserProfileResponse profile,
                                                      UserStatsResponse stats,
                                                      List<String> topGenres,
                                                      List<HistoryItemDto> history,
                                                      List<Map<String, Object>> feedbackSignals,
                                                      List<TrackDto> candidateTracks) {
        List<AiPromptMessage> messages = new ArrayList<>();
        messages.add(new AiPromptMessage(
                "system",
                """
                        你是 ScenePulse 的音乐推荐助手。
                        你的任务是根据用户当前诉求、用户画像和候选歌曲，生成一份可直接播放的推荐歌单。
                        你必须只从给定的候选歌曲中挑选，不要虚构歌曲。
                        最终输出必须是严格 JSON，对象结构如下：
                        {
                          "reply": "给用户的自然语言回复",
                          "playlist_title": "歌单标题",
                          "playlist_summary": "歌单摘要",
                          "recommendation_reason": "整体推荐说明",
                          "tags": ["标签1", "标签2"],
                          "selected_track_ids": [1,2,3],
                          "track_reasons": {
                            "1": "为什么选这首歌",
                            "2": "为什么选这首歌"
                          }
                        }
                        规则：
                        1. selected_track_ids 必须来自候选歌曲列表里的 track_id。
                        2. 至少选择 4 首，最多选择 8 首。
                        3. reply、playlist_title、playlist_summary 使用简体中文。
                        4. 如果用户要求的风格和候选不完全一致，请在 reply 中诚实说明，并尽量从候选里选最接近的。
                        5. 不要返回示例内容，不要解释 JSON 规则，不要使用 Markdown，只输出一个可被解析的 JSON 对象。
                        """
        ));

        List<AssistantMessageDto> historyMessages = request.history() == null ? List.of() : request.history();
        for (AssistantMessageDto item : historyMessages.stream().limit(6).toList()) {
            String content = item.content() == null ? "" : item.content().trim();
            if (!content.isBlank()) {
                messages.add(new AiPromptMessage(
                        "assistant".equalsIgnoreCase(item.role()) ? "assistant" : "user",
                        content
                ));
            }
        }

        messages.add(new AiPromptMessage(
                "user",
                buildUserPrompt(request, profile, stats, topGenres, history, feedbackSignals, candidateTracks)
        ));
        return messages;
    }

    private String buildUserPrompt(AssistantChatRequest request,
                                   UserProfileResponse profile,
                                   UserStatsResponse stats,
                                   List<String> topGenres,
                                   List<HistoryItemDto> history,
                                   List<Map<String, Object>> feedbackSignals,
                                   List<TrackDto> candidateTracks) {
        String candidateBlock = candidateTracks.stream()
                .map(track -> """
                        - track_id=%s | 标题=%s | 歌手=%s | 专辑=%s | 风格=%s | 简介=%s
                        """.formatted(
                        track.id(),
                        safeText(track.title()),
                        safeText(track.artist()),
                        safeText(track.album()),
                        safeText(track.genre()),
                        safeText(track.description())
                ))
                .collect(Collectors.joining());
        String historyBlock = history.isEmpty()
                ? "暂无"
                : history.stream()
                .map(item -> "- %s | %s | %s | %s | completed=%s".formatted(
                        safeText(item.title()),
                        safeText(item.artist()),
                        safeText(item.album()),
                        safeText(item.genre()),
                        item.completed()
                ))
                .collect(Collectors.joining("\n"));
        String feedbackBlock = feedbackSignals.isEmpty()
                ? "暂无"
                : feedbackSignals.stream()
                .map(item -> "- track_id=%s | 标题=%s | 评分=%s | 喜欢=%s | 跳过=%s | 来源=%s".formatted(
                        item.get("trackId"),
                        safeText(String.valueOf(item.get("title"))),
                        item.get("rating"),
                        item.get("liked"),
                        item.get("skipped"),
                        safeText(String.valueOf(item.get("source")))
                ))
                .collect(Collectors.joining("\n"));
        ZonedDateTime now = ZonedDateTime.now(resolveZone(profile == null ? null : profile.timezone()));

        return """
                用户当前需求：
                %s

                当前请求场景：
                - scene：%s
                - emotion：%s
                - style：%s
                - currentTrackId：%s
                - 当前时间：%s
                - 当前小时：%s

                用户画像：
                - 用户名：%s
                - 性别：%s
                - 最近 7 天播放：%s
                - 最近 30 天播放：%s
                - 最近 30 天跳过率：%.2f
                - 高频风格：%s
                - 最近播放记录数量：%s

                最近播放记录：
                %s

                最近收藏 / 评分 / 跳过反馈：
                %s

                当前候选歌曲如下，请严格只从这些候选里挑选：
                %s
                """.formatted(
                normalize(request.message(), "请帮我整理一份适合当前场景的歌单"),
                normalize(request.scene(), "default"),
                normalize(request.emotion(), "neutral"),
                blankToDefault(request.style(), "未指定"),
                request.currentTrackId() == null ? "未指定" : request.currentTrackId(),
                now,
                now.getHour(),
                profile == null ? "访客" : safeText(profile.username()),
                profile == null ? "未知" : safeText(profile.gender()),
                stats.playCount7d(),
                stats.playCount30d(),
                stats.skipRate30d(),
                topGenres.isEmpty() ? "暂无" : String.join(" / ", topGenres),
                history.size(),
                historyBlock,
                feedbackBlock,
                candidateBlock
        );
    }

    private ParsedAssistantReply parseAssistantReply(String content,
                                                     List<TrackDto> candidateTracks,
                                                     String userMessage,
                                                     List<String> reasoningSummary,
                                                     boolean forceFallback) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(stripCodeFence(content), new TypeReference<>() {});
            List<Long> selectedTrackIds = safeList((List<?>) parsed.get("selected_track_ids")).stream()
                    .map(value -> {
                        try {
                            return Long.parseLong(String.valueOf(value));
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            Map<String, String> trackReasons = parsed.get("track_reasons") instanceof Map<?, ?> map
                    ? map.entrySet().stream().collect(Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()),
                    entry -> String.valueOf(entry.getValue()),
                    (left, right) -> left,
                    LinkedHashMap::new
            ))
                    : Map.of();

            List<Long> validSelectedIds = selectedTrackIds.stream()
                    .filter(id -> candidateTracks.stream().anyMatch(track -> Objects.equals(track.id(), id)))
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new));
            for (TrackDto track : candidateTracks) {
                if (validSelectedIds.size() >= MIN_PLAYLIST_SIZE) {
                    break;
                }
                if (!validSelectedIds.contains(track.id())) {
                    validSelectedIds.add(track.id());
                }
            }

            List<AssistantPlaylistTrackDto> playlist = validSelectedIds.stream()
                    .map(id -> candidateTracks.stream()
                            .filter(track -> Objects.equals(track.id(), id))
                            .findFirst()
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .limit(MAX_PLAYLIST_SIZE)
                    .map(track -> new AssistantPlaylistTrackDto(
                            track,
                            trackReasons.getOrDefault(String.valueOf(track.id()), "这首歌和你当前的需求比较贴合。")
                    ))
                    .toList();

            if (playlist.isEmpty()) {
                return fallbackReply(candidateTracks, content, userMessage, reasoningSummary);
            }

            return new ParsedAssistantReply(
                    String.valueOf(parsed.getOrDefault("reply", "我已经根据你的需求整理了一份推荐歌单。")),
                    String.valueOf(parsed.getOrDefault("playlist_title", "为你生成的推荐歌单")),
                    String.valueOf(parsed.getOrDefault("playlist_summary", "这份歌单结合了你的偏好和当前场景。")),
                    String.valueOf(parsed.getOrDefault("recommendation_reason", parsed.getOrDefault("playlist_summary", "这份歌单结合了你的偏好和当前场景。"))),
                    playlist,
                    forceFallback,
                    parseTags(parsed, playlist),
                    reasoningSummary
            );
        } catch (Exception ignored) {
            return fallbackReply(candidateTracks, content, userMessage, reasoningSummary);
        }
    }

    private ParsedAssistantReply fallbackReply(List<TrackDto> candidateTracks,
                                               String content,
                                               String userMessage,
                                               List<String> reasoningSummary) {
        List<AssistantPlaylistTrackDto> playlist = candidateTracks.stream()
                .limit(6)
                .map(track -> new AssistantPlaylistTrackDto(track, "系统根据你的当前需求挑选了这首更贴近场景的歌曲。"))
                .toList();
        String reply = content == null || content.isBlank()
                ? "我先基于当前候选歌曲帮你整理了一份推荐歌单。"
                : content;
        return new ParsedAssistantReply(
                reply,
                "为你生成的推荐歌单",
                "这是根据你的提问“%s”整理出来的一份首版歌单。".formatted(normalize(userMessage, "当前场景")),
                "大模型结果不可用或格式不符合要求时，系统会直接使用候选歌曲池中排序靠前的本地推荐结果，保证推荐功能不中断。",
                playlist,
                true,
                inferTags(playlist),
                reasoningSummary == null ? List.of() : reasoningSummary
        );
    }

    private AssistantChatResponse toResponse(ParsedAssistantReply parsed) {
        List<TrackDto> songs = parsed.playlist().stream()
                .map(AssistantPlaylistTrackDto::track)
                .toList();
        return new AssistantChatResponse(
                parsed.reply(),
                parsed.reply(),
                parsed.playlistTitle(),
                parsed.playlistSummary(),
                parsed.recommendationReason(),
                parsed.playlist(),
                songs,
                parsed.tags(),
                properties.model(),
                parsed.usedFallback(),
                parsed.usedFallback(),
                parsed.reasoningSummary()
        );
    }

    private List<String> parseTags(Map<String, Object> parsed, List<AssistantPlaylistTrackDto> playlist) {
        Object tagsValue = parsed.get("tags");
        if (tagsValue instanceof List<?> values) {
            List<String> tags = values.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(6)
                    .toList();
            if (!tags.isEmpty()) {
                return tags;
            }
        }
        return inferTags(playlist);
    }

    private List<String> inferTags(List<AssistantPlaylistTrackDto> playlist) {
        return playlist.stream()
                .map(item -> item.track().genre())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .limit(4)
                .toList();
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

    private boolean isGuest(String userId) {
        return userId == null || userId.isBlank() || userId.startsWith("guest");
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String safeText(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? "暂无" : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String truncate(String value, int maxLength) {
        String normalized = normalize(value, "");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private ZoneId resolveZone(String timezone) {
        try {
            return ZoneId.of(normalize(timezone, "Asia/Shanghai"));
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> safeList(List<?> value) {
        return value == null ? List.of() : (List<T>) value;
    }

    private record ParsedAssistantReply(
            String reply,
            String playlistTitle,
            String playlistSummary,
            String recommendationReason,
            List<AssistantPlaylistTrackDto> playlist,
            boolean usedFallback,
            List<String> tags,
            List<String> reasoningSummary
    ) {
    }
}
