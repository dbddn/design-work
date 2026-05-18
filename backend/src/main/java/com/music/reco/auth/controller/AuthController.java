package com.music.reco.auth.controller;

import com.music.reco.auth.dto.AuthResponse;
import com.music.reco.auth.dto.LoginRequest;
import com.music.reco.auth.dto.RegisterRequest;
import com.music.reco.auth.service.AuthService;
import com.music.reco.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/guest")
    public ApiResponse<AuthResponse> guest() {
        return ApiResponse.ok(authService.guest());
    }
}
