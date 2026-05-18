package com.music.reco.recommendation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RecommendationFeedbackRequest(
        @NotNull Long trackId,
        @Min(1) @Max(5) Integer rating,
        Boolean liked,
        Boolean skipped
) {
}
