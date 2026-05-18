package com.music.reco.music.dto;

public record MusicCommentDto(
        Long id,
        Long musicId,
        String userId,
        String username,
        String content,
        Integer likeCount,
        Integer replyCount,
        String createdAt
) {
}
