package com.music.reco.recommendation.service;

import com.music.reco.mcp.dto.McpHybridRecommendationArgs;
import com.music.reco.recommendation.dto.UserPreferenceContext;
import com.music.reco.recommendation.strategy.StrategySnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class McpQueryMappingService {
    private static final Map<String, String> GENRE_TRANSLATION = createGenreTranslation();
    private static final int SPARSE_USER_PLAY_THRESHOLD = 30;
    private static final int MATURE_USER_PLAY_THRESHOLD = 80;
    private static final int CONFIDENT_FEEDBACK_THRESHOLD = 5;
    private static final double HIGH_SKIP_RATE_THRESHOLD = 0.35d;

    public McpHybridRecommendationArgs buildRecommendationArgs(String scene,
                                                               String emotion,
                                                               int limit,
                                                               StrategySnapshot snapshot,
                                                               UserPreferenceContext context) {
        List<String> translatedTopGenres = translateGenres(context.topGenres());
        List<String> mergedGenres = merge(sceneGenres(scene), emotionGenres(emotion), translatedTopGenres);
        List<String> mergedDescriptors = merge(sceneDescriptors(scene), emotionDescriptors(emotion));
        boolean emptyColdStart = isDefaultNeutral(scene, emotion)
                && safeList(context.recentAnchorAlbums()).isEmpty()
                && translatedTopGenres.isEmpty();

        return new McpHybridRecommendationArgs(
                safeList(context.recentAnchorAlbums()),
                null,
                emptyColdStart ? List.of() : trimToSize(mergedGenres, 3),
                List.of(),
                emptyColdStart ? List.of() : trimToSize(mergedDescriptors, 5),
                resolvePersonalizedMinRating(context, snapshot.explorationRatio()),
                Math.max(5, Math.min(limit, 30)),
                resolveHybridWeight(snapshot, context)
        );
    }

    public McpHybridRecommendationArgs buildTimeSlotRecommendationArgs(String timeSlot,
                                                                       String scene,
                                                                       String emotion,
                                                                       int limit,
                                                                       StrategySnapshot snapshot,
                                                                       UserPreferenceContext context) {
        String resolvedScene = resolveTimeSlotScene(timeSlot, scene);
        String resolvedEmotion = resolveTimeSlotEmotion(timeSlot, emotion);
        McpHybridRecommendationArgs base = buildRecommendationArgs(resolvedScene, resolvedEmotion, limit, snapshot, context);

        List<String> globalTimeSlotGenres = translateGenres(safeMapList(context.globalTimeSlotGenres(), timeSlot));
        List<String> mergedGenres = merge(globalTimeSlotGenres, base.genres(), timeSlotGenres(timeSlot));
        List<String> mergedDescriptors = merge(base.descriptors(), timeSlotDescriptors(timeSlot));

        return new McpHybridRecommendationArgs(
                resolveTimeSlotAnchors(base.similarTo(), context),
                base.between(),
                trimToSize(mergedGenres, 3),
                List.of(),
                trimToSize(mergedDescriptors, 4),
                Math.max(resolveTimeSlotMinRating(timeSlot), resolvePersonalizedMinRating(context, snapshot.explorationRatio())),
                base.maxResults(),
                base.hybridWeight()
        );
    }

    private List<String> sceneGenres(String scene) {
        return switch (normalize(scene)) {
            case "commute" -> List.of("pop");
            case "workout" -> List.of("electronic");
            case "study", "sleep" -> List.of("ambient");
            default -> List.of();
        };
    }

    private List<String> sceneDescriptors(String scene) {
        return switch (normalize(scene)) {
            case "commute" -> List.of("energetic", "fresh", "urban");
            case "workout" -> List.of("intense", "driving", "uplifting");
            case "study" -> List.of("focused", "calm", "steady");
            case "sleep" -> List.of("soft", "peaceful", "relaxing");
            default -> List.of();
        };
    }

    private List<String> emotionGenres(String emotion) {
        return switch (normalize(emotion)) {
            case "happy" -> List.of("pop");
            case "sad", "focus" -> List.of("ambient");
            case "energetic" -> List.of("electronic");
            default -> List.of();
        };
    }

    private List<String> emotionDescriptors(String emotion) {
        return switch (normalize(emotion)) {
            case "happy" -> List.of("joyful", "bright");
            case "sad" -> List.of("melancholic", "nostalgic");
            case "focus" -> List.of("focused", "minimal");
            case "energetic" -> List.of("exciting", "driving");
            default -> List.of("balanced");
        };
    }

    private Double resolveHybridWeight(StrategySnapshot snapshot, UserPreferenceContext context) {
        double content = snapshot.hybridWeight().getOrDefault("content", 0.30d);
        double scene = snapshot.hybridWeight().getOrDefault("scene", 0.20d);
        double collaborative = snapshot.hybridWeight().getOrDefault("collaborative", 0.10d);
        double feedbackConfidence = Math.min(0.12d, context.feedbackCount() * 0.012d);
        double positiveBias = Math.min(0.06d, context.positiveFeedbackCount() * 0.012d);
        double negativePenalty = Math.min(0.10d, context.negativeFeedbackCount() * 0.015d);

        double resolved;
        if (context.playCount30d() == 0) {
            resolved = 0.52d + scene * 0.20d;
        } else if (context.playCount30d() < SPARSE_USER_PLAY_THRESHOLD) {
            resolved = 0.58d + content * 0.18d + scene * 0.16d + positiveBias;
        } else if (context.playCount30d() < MATURE_USER_PLAY_THRESHOLD) {
            resolved = 0.64d + content * 0.12d + collaborative * 0.22d + feedbackConfidence;
        } else {
            resolved = 0.72d + collaborative * 0.18d + feedbackConfidence + positiveBias;
        }

        if (context.skipRate30d() > HIGH_SKIP_RATE_THRESHOLD) {
            resolved -= 0.06d;
        }
        resolved -= negativePenalty;
        return clamp(resolved, 0.45d, 0.88d);
    }

    private String resolveTimeSlotScene(String timeSlot, String scene) {
        if (scene != null && !scene.isBlank() && !"default".equalsIgnoreCase(scene.trim())) {
            return scene;
        }
        return switch (normalize(timeSlot)) {
            case "morning" -> "study";
            case "afternoon" -> "default";
            case "evening" -> "sleep";
            case "midnight" -> "sleep";
            default -> "default";
        };
    }

    private String resolveTimeSlotEmotion(String timeSlot, String emotion) {
        if (emotion != null && !emotion.isBlank() && !"neutral".equalsIgnoreCase(emotion.trim())) {
            return emotion;
        }
        return switch (normalize(timeSlot)) {
            case "morning" -> "energetic";
            case "afternoon" -> "focus";
            case "evening" -> "neutral";
            case "midnight" -> "sad";
            default -> "neutral";
        };
    }

    private List<String> timeSlotGenres(String timeSlot) {
        return switch (normalize(timeSlot)) {
            case "morning" -> List.of("pop", "electronic");
            case "afternoon" -> List.of("ambient", "pop");
            case "evening" -> List.of("ambient", "melodic");
            case "midnight" -> List.of("ambient", "electronic");
            default -> List.of();
        };
    }

    private List<String> timeSlotDescriptors(String timeSlot) {
        return switch (normalize(timeSlot)) {
            case "morning" -> List.of("fresh", "uplifting", "bright", "focused");
            case "afternoon" -> List.of("steady", "light", "focused", "smooth");
            case "evening" -> List.of("warm", "relaxing", "soft", "melodic");
            case "midnight" -> List.of("quiet", "immersive", "minimal", "peaceful");
            default -> List.of();
        };
    }

    private Double resolveTimeSlotMinRating(String timeSlot) {
        return switch (normalize(timeSlot)) {
            case "morning" -> 3.4d;
            case "afternoon" -> 3.5d;
            case "evening" -> 3.3d;
            case "midnight" -> 3.7d;
            default -> 3.0d;
        };
    }

    private Double resolvePersonalizedMinRating(UserPreferenceContext context, double explorationRatio) {
        double minRating;
        if (context.playCount30d() == 0) {
            minRating = 3.0d;
        } else if (context.playCount30d() < SPARSE_USER_PLAY_THRESHOLD) {
            minRating = 3.2d;
        } else if (context.playCount30d() < MATURE_USER_PLAY_THRESHOLD) {
            minRating = 3.5d;
        } else {
            minRating = 3.8d;
        }

        if (context.feedbackCount() >= CONFIDENT_FEEDBACK_THRESHOLD
                && context.positiveFeedbackCount() > context.negativeFeedbackCount() * 2) {
            minRating -= 0.1d;
        }
        if (context.skipRate30d() > HIGH_SKIP_RATE_THRESHOLD
                || context.negativeFeedbackCount() > context.positiveFeedbackCount()) {
            minRating += 0.25d;
        }
        if (explorationRatio >= 0.28d) {
            minRating -= 0.15d;
        }
        return clamp(minRating, 3.0d, 4.2d);
    }

    private List<String> resolveTimeSlotAnchors(List<String> baseAnchors, UserPreferenceContext context) {
        if (context.playCount30d() == 0) {
            return List.of();
        }
        int limit = context.playCount30d() < SPARSE_USER_PLAY_THRESHOLD ? 1 : 3;
        return trimToSize(safeList(baseAnchors), limit);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @SafeVarargs
    private List<String> merge(List<String>... groups) {
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> group : groups) {
            for (String item : safeList(group)) {
                if (item != null && !item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private List<String> trimToSize(List<String> values, int size) {
        return values.size() <= size ? values : values.subList(0, size);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDefaultNeutral(String scene, String emotion) {
        return "default".equalsIgnoreCase(scene == null ? "" : scene.trim())
                && "neutral".equalsIgnoreCase(emotion == null ? "" : emotion.trim());
    }

    private List<String> translateGenres(List<String> genres) {
        List<String> translated = new ArrayList<>();
        for (String genre : safeList(genres)) {
            String normalized = genre == null ? "" : genre.trim();
            String mapped = GENRE_TRANSLATION.get(normalized);
            if (mapped != null && !mapped.isBlank()) {
                translated.add(mapped);
            }
        }
        return translated;
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private List<String> safeMapList(Map<String, List<String>> values, String key) {
        if (values == null || key == null) {
            return List.of();
        }
        return safeList(values.get(normalize(key)));
    }

    private static Map<String, String> createGenreTranslation() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("清新", "ambient");
        map.put("治愈", "ambient");
        map.put("欢快", "pop");
        map.put("孤寂", "ambient");
        map.put("忧郁", "ambient");
        map.put("思念", "nostalgic");
        map.put("怀旧", "nostalgic");
        map.put("宁静", "ambient");
        map.put("舒缓", "ambient");
        map.put("浪漫", "melodic");
        map.put("感动", "melodic");
        map.put("激昂", "electronic");
        map.put("清新", "ambient");
        map.put("治愈", "ambient");
        map.put("欢快", "pop");
        map.put("孤寂", "ambient");
        map.put("忧郁", "ambient");
        map.put("思念", "nostalgic");
        map.put("怀旧", "nostalgic");
        map.put("宁静", "ambient");
        map.put("舒缓", "ambient");
        map.put("浪漫", "melodic");
        map.put("感动", "melodic");
        map.put("激昂", "electronic");
        map.put("其他风格", "ambient");
        return map;
    }
}
