package com.music.reco.user.dto;

public record UserStatsResponse(
        int playCount7d,
        int playCount30d,
        double skipRate30d,
        int likeCount30d
) {
}
