package com.music.reco.auth.dto;

public record AuthUserRecord(
        String userId,
        String username,
        String email,
        String passwordHash,
        String status
) {
}
