package com.music.reco.music.dto;

import java.time.Instant;

public record CommunityPostDto(
        Long postId,
        Long userId,
        String username,
        String content,
        Integer likeCount,
        Integer commentCount,
        Instant createdAt
) {
}
