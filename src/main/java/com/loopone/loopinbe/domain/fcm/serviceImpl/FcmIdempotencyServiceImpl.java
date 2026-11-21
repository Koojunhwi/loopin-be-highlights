package com.loopone.loopinbe.domain.fcm.serviceImpl;

import com.loopone.loopinbe.domain.fcm.service.FcmIdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmIdempotencyServiceImpl implements FcmIdempotencyService {
    private static final String KEY_PREFIX = "fcm:dedup:";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(3);
    private static final Duration SENT_TTL = Duration.ofDays(2);
    private static final String SENT_VALUE = "SENT";

    private static final String COMPARE_AND_DELETE = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private static final String COMPARE_AND_SET_SENT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('set', KEYS[1], ARGV[2])
                redis.call('expire', KEYS[1], ARGV[3])
                return 1
            else
                return 0
            end
            """;

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>(COMPARE_AND_DELETE, Long.class);
    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SENT_SCRIPT =
            new DefaultRedisScript<>(COMPARE_AND_SET_SENT, Long.class);

    private final StringRedisTemplate redisTemplate;

    // 처음 처리하는 eventId면 고유 토큰 반환, 이미 처리(또는 처리중)이면 empty
    public Optional<String> tryAcquire(String eventId) {
        String key = KEY_PREFIX + eventId;
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, PROCESSING_TTL);
        return Boolean.TRUE.equals(ok) ? Optional.of(token) : Optional.empty();
    }

    // 처리 성공 표시: 내 토큰일 때만 SENT로 전환
    public void markSuccess(String eventId, String token) {
        String key = KEY_PREFIX + eventId;
        redisTemplate.execute(COMPARE_AND_SET_SENT_SCRIPT, List.of(key),
                token, SENT_VALUE, String.valueOf(SENT_TTL.getSeconds()));
    }

    // 처리 실패 시: 내 토큰일 때만 락 해제
    public void release(String eventId, String token) {
        String key = KEY_PREFIX + eventId;
        redisTemplate.execute(COMPARE_AND_DELETE_SCRIPT, List.of(key), token);
    }
}
