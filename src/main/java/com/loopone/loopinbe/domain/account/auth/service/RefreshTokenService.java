package com.loopone.loopinbe.domain.account.auth.service;

import java.time.Duration;

public interface RefreshTokenService {
    // Refresh Token 저장
    void saveRefreshToken(Long memberId, String refreshToken, Duration duration);

    // Refresh Token 조회
    String getRefreshToken(Long memberId);

    // Refresh Token 삭제
    void deleteRefreshToken(Long memberId);
}
