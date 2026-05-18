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

    public McpHybridRecommendationArgs buildRecommendationArgs(String scene,
                                                               String emotion,
                                                               int limit,
                                                               StrategySnapshot snapshot,
                                                               UserPreferenceContext context) {
        List<String> translatedTopGenres = translateGenres(context.topGenres());
        List<String> mergedGenres = merge(sceneGenres(scene), emotionGenres(emotion), translatedTopGenres);
        List<String> mergedDescriptors = merge(sceneDescriptors(scene), emotionDescriptors(emotion));
        boolean emptyColdStart = isDefaultNeutral(scene, emotion) && safeList(context.recentAnchorAlbums()).isEmpty();

        return new McpHybridRecommendationArgs(
                safeList(context.recentAnchorAlbums()),
                null,
                emptyColdStart ? List.of() : trimToSize(mergedGenres, 3),
                List.of(),
                emptyColdStart ? List.of() : trimToSize(mergedDescriptors, 5),
                resolveMinRating(snapshot.explorationRatio(), context.skipRate30d()),
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

        List<String> mergedGenres = merge(base.genres(), timeSlotGenres(timeSlot));
        List<String> mergedDescriptors = merge(base.descriptors(), timeSlotDescriptors(timeSlot));

        return new McpHybridRecommendationArgs(
                List.of(),
                base.between(),
                trimToSize(mergedGenres, 3),
                List.of(),
                trimToSize(mergedDescriptors, 4),
                base.minRating() == null ? 3.0d : Math.min(3.0d, base.minRating()),
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

    private Double resolveMinRating(double explorationRatio, double skipRate30d) {
        if (explorationRatio >= 0.30d) {
            return 3.0d;
        }
        if (skipRate30d > 0.35d) {
            return 4.0d;
        }
        return 3.5d;
    }

    private Double resolveHybridWeight(StrategySnapshot snapshot, UserPreferenceContext context) {
        if (context.coldStart()) {
            return 0.68d;
        }
        double content = snapshot.hybridWeight().getOrDefault("content", 0.30d);
        double scene = snapshot.hybridWeight().getOrDefault("scene", 0.20d);
        double resolved = content + scene * 0.55d;
        if (context.skipRate30d() > 0.35d) {
            resolved += 0.08d;
        }
        return Math.max(0.20d, Math.min(0.85d, resolved));
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
        return map;
    }
}
