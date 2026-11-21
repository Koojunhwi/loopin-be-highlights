package com.loopone.loopinbe.global.kafka.event.fcm.serviceImpl;

import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmIdempotencyService;
import com.loopone.loopinbe.domain.fcm.service.FcmService;
import com.loopone.loopinbe.global.kafka.event.fcm.service.FcmEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmEventServiceImpl implements FcmEventService {

    private final FcmService fcmService;
    private final FcmIdempotencyService fcmIdempotencyService;

    @Override
    public void processFcmEvent(FcmMessageResponse message) {
        // 1) 검증
        if (!StringUtils.hasText(message.getEventId())) {
            throw new IllegalArgumentException("FCM eventId is blank");
        }
        if (!StringUtils.hasText(message.getTargetToken())) {
            throw new IllegalArgumentException("FCM targetToken is blank");
        }

        // 2) 멱등 선점 — Redis 장애 시 fail-open
        String token = null;
        try {
            Optional<String> acquired = fcmIdempotencyService.tryAcquire(message.getEventId());
            if (acquired.isEmpty()) {
                log.info("Duplicate FCM event skipped. eventId={}", message.getEventId());
                return;
            }
            token = acquired.get();
        } catch (Exception e) {
            log.warn("Redis unavailable, sending without idempotency. eventId={}", message.getEventId(), e);
        }

        // 3) FCM 발송
        try {
            fcmService.sendMessageTo(message);
        } catch (Exception e) {
            safeRelease(message.getEventId(), token);
            log.error("Failed to send FCM. eventId={}", message.getEventId(), e);
            throw e;
        }

        // 4) 성공 마킹 — 발송은 이미 성공했으므로 Redis 오류가 재시도로 이어지면 안 됨
        safeMarkSuccess(message.getEventId(), token);
    }

    private void safeMarkSuccess(String eventId, String token) {
        if (token == null) return;
        try {
            fcmIdempotencyService.markSuccess(eventId, token);
        } catch (Exception e) {
            log.warn("Failed to mark success. eventId={}", eventId, e);
        }
    }

    private void safeRelease(String eventId, String token) {
        if (token == null) return;
        try {
            fcmIdempotencyService.release(eventId, token);
        } catch (Exception e) {
            log.warn("Failed to release. eventId={}", eventId, e);
        }
    }
}
