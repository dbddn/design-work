package com.music.reco.music.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.CommunityCommentDto;
import com.music.reco.music.dto.CommunityPostDto;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CommunityService {
    private final LegacyJdbcRepository legacyJdbcRepository;

    public CommunityService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public CommunityPostDto createPost(String userId, String content) {
        return legacyJdbcRepository.createCommunityPost(userId, content);
    }

    public List<CommunityPostDto> listPosts() {
        return legacyJdbcRepository.listCommunityPosts();
    }

    public List<CommunityCommentDto> listComments(Long postId) {
        return legacyJdbcRepository.listCommunityComments(postId);
    }

    public Map<String, Object> createComment(Long postId, String userId, String content) {
        return legacyJdbcRepository.createComment(postId, userId, content);
    }

    public Map<String, Object> like(Long postId, String userId) {
        return legacyJdbcRepository.likePost(postId, userId);
    }
}
