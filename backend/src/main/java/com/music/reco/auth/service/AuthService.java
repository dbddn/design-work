package com.music.reco.auth.service;

import com.music.reco.auth.dto.AuthResponse;
import com.music.reco.auth.dto.AuthUserRecord;
import com.music.reco.auth.dto.LoginRequest;
import com.music.reco.auth.dto.RegisterRequest;
import com.music.reco.common.exception.BusinessException;
import com.music.reco.common.exception.ErrorCode;
import com.music.reco.config.JwtProperties;
import com.music.reco.legacy.LegacyJdbcRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class AuthService {
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    public AuthService(LegacyJdbcRepository legacyJdbcRepository,
                       JwtService jwtService,
                       JwtProperties jwtProperties,
                       PasswordEncoder passwordEncoder) {
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthResponse register(RegisterRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "username is required");
        }
        String username = request.username().trim();
        String email = request.email().trim();
        if (legacyJdbcRepository.findUserByUsername(username).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "username already exists");
        }
        if (legacyJdbcRepository.existsUserByEmail(email)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "email already exists");
        }
        String passwordHash = passwordEncoder.encode(request.password().trim());
        String userId = legacyJdbcRepository.createUser(username, email, passwordHash);
        String token = jwtService.generateToken(userId, username, false);
        return new AuthResponse(token, "Bearer", jwtProperties.jwtExpireSeconds(), userId, username, false);
    }

    public AuthResponse login(LoginRequest request) {
        try {
            AuthUserRecord user = legacyJdbcRepository.findAuthUserByUsername(request.username().trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "invalid credentials"));
            if (user.status() != null && !"ACTIVE".equalsIgnoreCase(user.status().trim())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "user is disabled");
            }
            String rawPassword = request.password().trim();
            if (user.passwordHash() != null && !user.passwordHash().isBlank()) {
                if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED, "invalid credentials");
                }
            }
            legacyJdbcRepository.updateLastLoginAt(user.userId());
            String token = jwtService.generateToken(user.userId(), user.username(), false);
            return new AuthResponse(token, "Bearer", jwtProperties.jwtExpireSeconds(), user.userId(), user.username(), false);
        } catch (BusinessException error) {
            throw error;
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "数据库连接超时，请确认 MySQL 服务已启动并稍后重试");
        }
    }

    public AuthResponse guest() {
        String guestId = "guest_" + System.currentTimeMillis();
        String guestName = "guest";
        String token = jwtService.generateToken(guestId, guestName, true);
        return new AuthResponse(token, "Bearer", jwtProperties.jwtExpireSeconds(), guestId, guestName, true);
    }
}
