package com.music.reco.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.music.reco.assistant.dto.AiCompletionResult;
import com.music.reco.assistant.dto.AiPromptMessage;
import com.music.reco.assistant.dto.AssistantChatRequest;
import com.music.reco.assistant.dto.AssistantChatResponse;
import com.music.reco.assistant.dto.AssistantMessageDto;
import com.music.reco.assistant.dto.AssistantPlaylistTrackDto;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.config.AssistantModelProperties;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.HistoryItemDto;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.recommendation.dto.RecommendationResponse;
import com.music.reco.recommendation.service.RecommendationService;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AssistantService {
    private static final int CANDIDATE_LIMIT = 12;

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
        if (!assistantLlmClientService.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AI 助手尚未配置完成，请先设置应用模型与 API Key");
        }

        String scene = normalize(request.scene(), "default");
        String emotion = normalize(request.emotion(), "neutral");
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
        List<TrackDto> candidateTracks = resolveCandidates(userId, scene, emotion);

        try {
            AiCompletionResult llmReply = assistantLlmClientService.complete(
                    buildPromptMessages(request, profile, stats, topGenres, history, candidateTracks)
            );
            ParsedAssistantReply parsed = parseAssistantReply(
                    llmReply.content(),
                    candidateTracks,
                    request.message(),
                    llmReply.reasoningSummary()
            );
            return toResponse(parsed);
        } catch (BusinessException error) {
            return toResponse(fallbackReply(
                    candidateTracks,
                    "当前 AI 助手暂时不可用，我先根据系统候选结果为你整理了一份本地推荐歌单。",
                    request.message(),
                    List.of("本次已自动回退到系统候选池，方便你继续试听和挑选。")
            ));
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
                buildUserPrompt(request.message(), profile, stats, topGenres, history, candidateTracks)
        ));
        return messages;
    }

    private String buildUserPrompt(String userMessage,
                                   UserProfileResponse profile,
                                   UserStatsResponse stats,
                                   List<String> topGenres,
                                   List<HistoryItemDto> history,
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

        return """
                用户当前需求：
                %s

                用户画像：
                - 用户名：%s
                - 性别：%s
                - 最近 7 天播放：%s
                - 最近 30 天播放：%s
                - 最近 30 天跳过率：%.2f
                - 高频风格：%s
                - 最近播放记录数量：%s

                当前候选歌曲如下，请严格只从这些候选里挑选：
                %s
                """.formatted(
                normalize(userMessage, "请帮我整理一份适合当前场景的歌单"),
                profile == null ? "访客" : safeText(profile.username()),
                profile == null ? "未知" : safeText(profile.gender()),
                stats.playCount7d(),
                stats.playCount30d(),
                stats.skipRate30d(),
                topGenres.isEmpty() ? "暂无" : String.join(" / ", topGenres),
                history.size(),
                candidateBlock
        );
    }

    private ParsedAssistantReply parseAssistantReply(String content,
                                                     List<TrackDto> candidateTracks,
                                                     String userMessage,
                                                     List<String> reasoningSummary) {
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

            List<AssistantPlaylistTrackDto> playlist = candidateTracks.stream()
                    .filter(track -> selectedTrackIds.contains(track.id()))
                    .limit(8)
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
                    playlist,
                    false,
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
                playlist,
                true,
                reasoningSummary == null ? List.of() : reasoningSummary
        );
    }

    private AssistantChatResponse toResponse(ParsedAssistantReply parsed) {
        return new AssistantChatResponse(
                parsed.reply(),
                parsed.playlistTitle(),
                parsed.playlistSummary(),
                parsed.playlist(),
                properties.model(),
                parsed.usedFallback(),
                parsed.reasoningSummary()
        );
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
        return value == null || value.isBlank() ? "暂无" : value.trim();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> safeList(List<?> value) {
        return value == null ? List.of() : (List<T>) value;
    }

    private record ParsedAssistantReply(
            String reply,
            String playlistTitle,
            String playlistSummary,
            List<AssistantPlaylistTrackDto> playlist,
            boolean usedFallback,
            List<String> reasoningSummary
    ) {
    }
}
