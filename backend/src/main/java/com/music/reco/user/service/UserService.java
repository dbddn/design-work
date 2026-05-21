package com.music.reco.user.service;

import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.user.dto.UpdateUserProfileRequest;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

@Service
public class UserService {
    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    private final LegacyJdbcRepository legacyJdbcRepository;

    public UserService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public UserProfileResponse getMe(String userId) {
        if (userId == null || userId.isBlank() || userId.startsWith("guest")) {
            if (userId != null && !userId.isBlank()) {
                var storedProfile = legacyJdbcRepository.findUserProfile(userId);
                if (storedProfile.isPresent()) {
                    return storedProfile.get();
                }
            }
            return new UserProfileResponse(
                    userId == null || userId.isBlank() ? "guest" : userId,
                    "guest",
                    null,
                    "访客",
                    "Asia/Shanghai",
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()
            );
        }
        return legacyJdbcRepository.findUserProfile(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "user not found"));
    }

    public UserProfileResponse updateMe(String userId, UpdateUserProfileRequest request) {
        ensureWritableUser(userId, request.username());
        UserProfileResponse user = getMe(userId);
        String nextUsername = request.username() == null || request.username().isBlank() ? user.username() : request.username().trim();
        String nextEmail = request.email() == null || request.email().isBlank() ? user.email() : request.email().trim();
        String nextTimezone = request.timezone() == null || request.timezone().isBlank() ? "Asia/Shanghai" : request.timezone().trim();
        String nextGender = request.gender() == null || request.gender().isBlank() ? user.gender() : request.gender().trim();
        String nextAgeRange = request.ageRange() == null || request.ageRange().isBlank() ? user.ageRange() : request.ageRange().trim();
        String nextProvince = request.province() == null || request.province().isBlank() ? user.province() : request.province().trim();
        String nextAvatarUrl = request.avatarUrl() == null || request.avatarUrl().isBlank() || request.avatarUrl().startsWith("data:")
                ? null
                : request.avatarUrl().trim();
        String nextBio = request.bio() == null || request.bio().isBlank() ? user.bio() : request.bio().trim();

        if (!nextUsername.equals(user.username()) && legacyJdbcRepository.findUserByUsername(nextUsername).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "username already exists");
        }

        legacyJdbcRepository.updateUserProfile(
                userId,
                nextUsername,
                nextEmail,
                nextTimezone,
                nextGender,
                nextAgeRange,
                nextProvince,
                nextAvatarUrl,
                nextBio
        );
        return getMe(userId);
    }

    public UserProfileResponse updateAvatar(String userId, MultipartFile avatar) {
        ensureWritableUser(userId, null);
        getMe(userId);
        if (avatar == null || avatar.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的头像");
        }
        if (avatar.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像不能超过 2MB");
        }

        String contentType = avatar.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件必须是图片");
        }

        try {
            String encoded = Base64.getEncoder().encodeToString(avatar.getBytes());
            legacyJdbcRepository.updateUserAvatarData(userId, "data:" + contentType + ";base64," + encoded);
            return getMe(userId);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像上传失败，请重试");
        }
    }

    public UserStatsResponse getStats(String userId) {
        if (userId == null || userId.isBlank() || userId.startsWith("guest")) {
            return new UserStatsResponse(0, 0, 0, 0);
        }
        getMe(userId);
        return legacyJdbcRepository.buildUserStats(userId);
    }

    private void ensureWritableUser(String userId, String preferredUsername) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录后再修改资料");
        }
        if (legacyJdbcRepository.findUserProfile(userId).isPresent()) {
            return;
        }
        if (!userId.startsWith("guest")) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }

        String fallbackUsername = userId.replaceAll("[^A-Za-z0-9_]", "_");
        String username = preferredUsername == null || preferredUsername.isBlank()
                ? fallbackUsername
                : preferredUsername.trim();
        if (legacyJdbcRepository.findUserByUsername(username).isPresent()) {
            username = fallbackUsername;
        }
        String email = userId.replaceAll("[^A-Za-z0-9_]", "_") + "@guest.local";
        legacyJdbcRepository.createUserShell(userId, username, email);
    }
}
