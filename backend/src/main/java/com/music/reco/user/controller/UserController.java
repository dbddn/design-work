package com.music.reco.user.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.user.dto.UpdateUserProfileRequest;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import com.music.reco.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserProfileResponse> updateAvatar(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                        @RequestPart("avatar") MultipartFile avatar) {
        return ApiResponse.ok(userService.updateAvatar(userId, avatar));
    }

    @GetMapping("/me/stats")
    public ApiResponse<UserStatsResponse> stats(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return ApiResponse.ok(userService.getStats(userId));
    }
}
