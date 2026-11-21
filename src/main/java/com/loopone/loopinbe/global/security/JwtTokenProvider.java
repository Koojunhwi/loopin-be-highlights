package com.loopone.loopinbe.global.security;

import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {
    private final SecretKey key;

    // SecretKey 초기화 (한 번만 생성)
    public JwtTokenProvider(@Value("${custom.jwt.secretKey}") String secretKey) {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    // JWT 생성 (subject = memberId: 이메일이 바뀌어도 토큰이 가리키는 회원은 그대로다)
    public String generateToken(Long memberId, String type, Duration expiration) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .id(UUID.randomUUID().toString())
                .claim("tokenType", type)
                .issuedAt(new Date())
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key, Jwts.SIG.HS256)  // 필드 key 사용
                .compact();
    }

    // ACCESS Token 검증
    public boolean validateAccessToken(String token) {
        return validateToken(token, "ACCESS");
    }

    // REFRESH Token 검증
    public boolean validateRefreshToken(String token) {
        return validateToken(token, "REFRESH");
    }

    // JWT 검증
    private boolean validateToken(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenType = claims.get("tokenType", String.class);
            if (!expectedType.equals(tokenType)) {
                log.warn("토큰 타입 불일치: expected={}, actual={}", expectedType, tokenType);
                return false;
            }
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("토큰이 만료되었습니다: {}", e.getMessage());
        }
        return false;
    }

    // memberId 추출 (subject가 숫자가 아니면 구버전 email 토큰 → 무효 처리)
    public Long getMemberIdFromToken(String token) {
        String subject = parse(token).getSubject();
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            log.warn("토큰 subject가 memberId 형식이 아닙니다. 재로그인이 필요합니다.");
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
    }

    // jti/TTL 헬퍼
    public String getJti(String token) {
        return parse(token).getId(); // 표준 jti
    }

    public long getRemainingSeconds(String token) {
        Date exp = parse(token).getExpiration();
        long diffMs = exp.getTime() - System.currentTimeMillis();
        return Math.max(0, diffMs / 1000);
    }

    // 공통 파서
    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
