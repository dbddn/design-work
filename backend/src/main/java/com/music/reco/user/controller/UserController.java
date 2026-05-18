package com.music.reco.user.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.user.dto.UpdateUserProfileRequest;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import com.music.reco.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return ApiResponse.ok(userService.getMe(userId));
    }

    @PutMapping("/me")
    public ApiResponse<UserProfileResponse> updateMe(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                     @Valid @RequestBody UpdateUserProfileRequest request) {
        return ApiResponse.ok(userService.updateMe(userId, request));
    }

    @GetMapping("/me/stats")
    public ApiResponse<UserStatsResponse> stats(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return ApiResponse.ok(userService.getStats(userId));
    }
}
