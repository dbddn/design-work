package com.music.reco.music.dto;

import java.time.Instant;

public record CommunityCommentDto(
        Long commentId,
        Long postId,
        Long userId,
        String username,
        String content,
        Instant createdAt
) {
}
