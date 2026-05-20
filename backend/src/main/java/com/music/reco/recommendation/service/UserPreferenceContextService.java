package com.music.reco.recommendation.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.recommendation.dto.UserPreferenceContext;
import com.music.reco.user.dto.UserStatsResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserPreferenceContextService {
    private static final int SPARSE_USER_THRESHOLD = 30;
    private static final int USER_PREFERENCE_LIMIT = 3;
    private static final int RECENT_FEEDBACK_LIMIT = 30;

    private final LegacyJdbcRepository legacyJdbcRepository;

    public UserPreferenceContextService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public UserPreferenceContext build(String userId,
                                       UserStatsResponse stats,
                                       List<String> onboardingGenres) {
        List<String> userTopGenres = legacyJdbcRepository.findUserTopGenreLabels(userId, USER_PREFERENCE_LIMIT);
        List<String> effectiveGenres = userTopGenres.isEmpty() ? safeList(onboardingGenres) : userTopGenres;
        List<Map<String, Object>> feedbackSignals =
                legacyJdbcRepository.findRecentFeedbackSignals(userId, RECENT_FEEDBACK_LIMIT);
        int positiveFeedbackCount = (int) feedbackSignals.stream().filter(this::isPositiveFeedback).count();
        int negativeFeedbackCount = (int) feedbackSignals.stream().filter(this::isNegativeFeedback).count();

        return new UserPreferenceContext(
                effectiveGenres,
                legacyJdbcRepository.findUserTopArtistNames(userId, USER_PREFERENCE_LIMIT),
                legacyJdbcRepository.findUserTopAlbums(userId, USER_PREFERENCE_LIMIT),
                legacyJdbcRepository.findRecentAnchorAlbums(userId, USER_PREFERENCE_LIMIT),
                stats.playCount30d(),
                stats.skipRate30d(),
                feedbackSignals.size(),
                positiveFeedbackCount,
                negativeFeedbackCount,
                legacyJdbcRepository.findGlobalTimeSlotTopGenreLabels(USER_PREFERENCE_LIMIT),
                legacyJdbcRepository.findGlobalTimeSlotTopArtistNames(USER_PREFERENCE_LIMIT),
                stats.playCount30d() < SPARSE_USER_THRESHOLD
        );
    }

    private boolean isPositiveFeedback(Map<String, Object> row) {
        Object liked = row.get("liked");
        Object rating = row.get("rating");
        return Boolean.TRUE.equals(liked)
                || (rating instanceof Number number && number.intValue() >= 4);
    }

    private boolean isNegativeFeedback(Map<String, Object> row) {
        Object skipped = row.get("skipped");
        Object rating = row.get("rating");
        return Boolean.TRUE.equals(skipped)
                || (rating instanceof Number number && number.intValue() > 0 && number.intValue() <= 2);
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }
}
