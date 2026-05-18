package com.music.reco.recommendation.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.recommendation.dto.DaypartRecommendationResponse;
import com.music.reco.recommendation.dto.McpRecommendationDebugResponse;
import com.music.reco.recommendation.dto.RecommendationFeedbackRequest;
import com.music.reco.recommendation.dto.RecommendationOnboardingRequest;
import com.music.reco.recommendation.dto.RecommendationResponse;
import com.music.reco.recommendation.dto.StrategySnapshotResponse;
import com.music.reco.recommendation.service.RecommendationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping
    public ApiResponse<RecommendationResponse> recommendations(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @RequestParam(defaultValue = "default") String scene,
            @RequestParam(defaultValue = "neutral") String emotion,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(recommendationService.recommend(userId, scene, emotion, limit));
    }

    @GetMapping("/dayparts")
    public ApiResponse<DaypartRecommendationResponse> daypartRecommendations(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @RequestParam(defaultValue = "default") String scene,
            @RequestParam(defaultValue = "neutral") String emotion,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(recommendationService.recommendDaypartPlaylists(userId, scene, emotion, limit));
    }

    @PostMapping("/onboarding/preferences")
    public ApiResponse<RecommendationResponse> saveOnboardingPreferences(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @Valid @RequestBody RecommendationOnboardingRequest request
    ) {
        return ApiResponse.ok(recommendationService.saveOnboardingPreferences(userId, request));
    }

    @GetMapping("/debug/mcp")
    public ApiResponse<McpRecommendationDebugResponse> debugMcp(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @RequestParam(defaultValue = "default") String scene,
            @RequestParam(defaultValue = "neutral") String emotion,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(recommendationService.debugMcpRecommendation(userId, scene, emotion, limit));
    }

    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                      @Valid @RequestBody RecommendationFeedbackRequest request) {
        recommendationService.feedback(userId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/strategy")
    public ApiResponse<StrategySnapshotResponse> strategy(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return ApiResponse.ok(recommendationService.strategySnapshot(userId));
    }

    @PostMapping("/strategy/recalculate")
    public ApiResponse<StrategySnapshotResponse> recalculate(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return ApiResponse.ok(recommendationService.recalculate(userId));
    }
}
