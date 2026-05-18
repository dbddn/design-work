package com.music.reco.recommendation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RecommendationOnboardingRequest(
        @NotEmpty(message = "至少选择一个偏好标签")
        List<String> genres,
        String gender
) {
}
