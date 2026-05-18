package com.music.reco.user.service;

import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.user.dto.UpdateUserProfileRequest;
import com.music.reco.user.dto.UserProfileResponse;
import com.music.reco.user.dto.UserStatsResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {
    private final LegacyJdbcRepository legacyJdbcRepository;

    public UserService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public UserProfileResponse getMe(String userId) {
        if (userId == null || userId.isBlank() || userId.startsWith("guest")) {
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
        UserProfileResponse user = getMe(userId);
        String nextUsername = request.username() == null || request.username().isBlank() ? user.username() : request.username().trim();
        String nextEmail = request.email() == null || request.email().isBlank() ? user.email() : request.email().trim();
        String nextTimezone = request.timezone() == null || request.timezone().isBlank() ? "Asia/Shanghai" : request.timezone().trim();
        String nextGender = request.gender() == null || request.gender().isBlank() ? user.gender() : request.gender().trim();
        String nextAgeRange = request.ageRange() == null || request.ageRange().isBlank() ? user.ageRange() : request.ageRange().trim();
        String nextProvince = request.province() == null || request.province().isBlank() ? user.province() : request.province().trim();
        String nextAvatarUrl = request.avatarUrl() == null || request.avatarUrl().isBlank() ? user.avatarUrl() : request.avatarUrl().trim();
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

    public UserStatsResponse getStats(String userId) {
        if (userId == null || userId.isBlank() || userId.startsWith("guest")) {
            return new UserStatsResponse(0, 0, 0, 0);
        }
        getMe(userId);
        return legacyJdbcRepository.buildUserStats(userId);
    }
}
