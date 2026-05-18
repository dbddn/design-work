package com.music.reco.music.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.music.dto.CommunityCommentDto;
import com.music.reco.music.dto.CommunityPostDto;
import com.music.reco.music.dto.CreateCommentRequest;
import com.music.reco.music.dto.CreatePostRequest;
import com.music.reco.music.service.CommunityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/community")
public class CommunityController {
    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @PostMapping("/posts")
    public ApiResponse<CommunityPostDto> createPost(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                    @Valid @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(communityService.createPost(userId, request.content()));
    }

    @GetMapping("/posts")
    public ApiResponse<List<CommunityPostDto>> posts() {
        return ApiResponse.ok(communityService.listPosts());
    }

    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<List<CommunityCommentDto>> comments(@PathVariable Long postId) {
        return ApiResponse.ok(communityService.listComments(postId));
    }

    @PostMapping("/posts/{postId}/comments")
    public ApiResponse<Map<String, Object>> comment(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                    @PathVariable Long postId,
                                                    @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.ok(communityService.createComment(postId, userId, request.content()));
    }

    @PostMapping("/posts/{postId}/likes")
    public ApiResponse<Map<String, Object>> like(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                 @PathVariable Long postId) {
        return ApiResponse.ok(communityService.like(postId, userId));
    }
}
