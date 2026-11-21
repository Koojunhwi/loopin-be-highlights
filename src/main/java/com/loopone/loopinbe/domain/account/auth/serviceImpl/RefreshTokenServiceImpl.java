package com.loopone.loopinbe.domain.account.auth.serviceImpl;

import com.loopone.loopinbe.domain.account.auth.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {
    private static final String KEY_PREFIX = "refresh:";

    private final RedisTemplate<String, Object> redisTemplate;

    // Refresh Token 저장
    @Override
    @Transactional
    public void saveRefreshToken(Long memberId, String refreshToken, Duration duration) {
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        ops.set(key(memberId), refreshToken, duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    // Refresh Token 조회
    @Override
    @Transactional(readOnly = true)
    public String getRefreshToken(Long memberId) {
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        Object value = ops.get(key(memberId));
        return value != null ? value.toString() : null;
    }

    // Refresh Token 삭제
    @Override
    @Transactional
    public void deleteRefreshToken(Long memberId) {
        redisTemplate.delete(key(memberId));
    }

    private String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }
}
