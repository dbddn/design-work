package com.music.reco.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
        @Size(max = 50) String username,
        @Size(max = 128) String email,
        @Size(max = 64) String timezone,
        @Size(max = 10) String gender,
        @Size(max = 20) String ageRange,
        @Size(max = 50) String province,
        @Size(max = 255) String avatarUrl,
        @Size(max = 255) String bio
) {
}
