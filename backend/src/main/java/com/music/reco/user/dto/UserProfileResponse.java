package com.music.reco.user.dto;

import java.util.List;

public record UserProfileResponse(
        String userId,
        String username,
        String email,
        String nickname,
        String timezone,
        String gender,
        String ageRange,
        String province,
        String avatarUrl,
        String bio,
        List<String> preferredGenres
) {
}
