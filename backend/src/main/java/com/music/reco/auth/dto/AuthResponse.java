package com.music.reco.auth.dto;

public record AuthResponse(
        String token,
        String tokenType,
        long expireInSeconds,
        String userId,
        String username,
        boolean guest
) {
}
