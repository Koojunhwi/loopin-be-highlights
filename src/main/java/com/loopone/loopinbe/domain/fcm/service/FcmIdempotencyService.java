package com.loopone.loopinbe.domain.fcm.service;

import java.util.Optional;

public interface FcmIdempotencyService {
    // 처음 처리하는 eventId면 고유 토큰 반환, 이미 처리(또는 처리중)이면 empty
    Optional<String> tryAcquire(String eventId);

    // 처리 성공 표시: 내 토큰일 때만 SENT로 전환
    void markSuccess(String eventId, String token);

    // 처리 실패 시: 내 토큰일 때만 락 해제
    void release(String eventId, String token);
}
