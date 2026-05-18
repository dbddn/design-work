package com.music.reco.auth.service;

import com.music.reco.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final JwtProperties jwtProperties;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String generateToken(String userId, String username, boolean guest) {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("guest", guest)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(jwtProperties.jwtExpireSeconds())))
                .signWith(key)
                .compact();
    }
}
