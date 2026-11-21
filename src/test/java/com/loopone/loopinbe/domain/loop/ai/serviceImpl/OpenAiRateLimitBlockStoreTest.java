package com.loopone.loopinbe.domain.loop.ai.serviceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiRateLimitBlockStoreTest {
    private static final Duration BLOCK_DURATION = Duration.ofSeconds(30);
    private static final String GPT5_KEY = "ai:ratelimit:gpt-5";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OpenAiRateLimitBlockStore blockStore;

    @BeforeEach
    void setUp() {
        blockStore = new OpenAiRateLimitBlockStore(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-5"})
    @DisplayName("차단 조회: 해당 프로바이더 키가 TRUE일 때 true를 반환한다")
    void isBlockedReturnsTrueWhenProviderKeyExists(String provider) {
        String key = "ai:ratelimit:" + provider;
        when(redisTemplate.hasKey(key)).thenReturn(Boolean.TRUE);

        assertThat(blockStore.isBlocked(provider)).isTrue();

        verify(redisTemplate).hasKey(key);
        verifyNoMoreInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-5"})
    @DisplayName("차단 조회: 해당 프로바이더 키가 FALSE이면 false를 반환한다")
    void isBlockedReturnsFalseWhenProviderKeyDoesNotExist(String provider) {
        String key = "ai:ratelimit:" + provider;
        when(redisTemplate.hasKey(key)).thenReturn(Boolean.FALSE);

        assertThat(blockStore.isBlocked(provider)).isFalse();

        verify(redisTemplate).hasKey(key);
        verifyNoMoreInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-5"})
    @DisplayName("차단 조회: 해당 프로바이더 키 조회 결과가 null이면 false를 반환한다")
    void isBlockedReturnsFalseWhenHasKeyReturnsNull(String provider) {
        String key = "ai:ratelimit:" + provider;
        when(redisTemplate.hasKey(key)).thenReturn(null);

        assertThat(blockStore.isBlocked(provider)).isFalse();

        verify(redisTemplate).hasKey(key);
        verifyNoMoreInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-5"})
    @DisplayName("만료 시각 조회: 해당 프로바이더 키의 문자열을 그대로 반환한다")
    void getBlockedUntilReturnsProviderValue(String provider) {
        String key = "ai:ratelimit:" + provider;
        String storedValue = "2026-05-29T00:00:02.000Z";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(storedValue);

        assertThat(blockStore.getBlockedUntil(provider)).isEqualTo(storedValue);

        verify(valueOperations).get(key);
        verifyNoMoreInteractions(valueOperations);
    }

    @Test
    @DisplayName("만료 시각 조회: 저장된 값이 없으면 null을 반환한다")
    void getBlockedUntilReturnsNullWhenValueIsAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(GPT5_KEY)).thenReturn(null);

        assertThat(blockStore.getBlockedUntil("gpt-5")).isNull();

        verify(valueOperations).get(GPT5_KEY);
        verifyNoMoreInteractions(valueOperations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-5"})
    @DisplayName("차단 저장: 값이 없으면 해당 프로바이더 키에 만료 시각과 동일한 TTL을 저장한다")
    void blockStoresExpirationAndTtlOnlyForRequestedProvider(String provider) {
        String key = "ai:ratelimit:" + provider;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);

        assertBlockStoresExpiration(provider, key, BLOCK_DURATION);
    }

    @Test
    @DisplayName("차단 저장: 기존 만료 시각이 새 후보보다 뒤면 차단을 단축하지 않는다")
    void blockDoesNotOverwriteLaterExpiration() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(GPT5_KEY)).thenReturn(Instant.now().plus(Duration.ofDays(1)).toString());

        blockStore.block("gpt-5", BLOCK_DURATION);

        verify(valueOperations).get(GPT5_KEY);
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verifyNoMoreInteractions(valueOperations);
    }

    @Test
    @DisplayName("차단 저장: 기존 만료 시각이 더 이르면 새 후보로 연장한다")
    void blockOverwritesEarlierExpiration() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(GPT5_KEY)).thenReturn(Instant.now().minusSeconds(60).toString());

        assertBlockStoresExpiration("gpt-5", GPT5_KEY, BLOCK_DURATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-instant", "", "   "})
    @DisplayName("차단 저장: 기존 값이 파싱 불가 문자열이면 새 후보로 저장한다")
    void blockOverwritesUnparseableExpiration(String storedValue) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(GPT5_KEY)).thenReturn(storedValue);

        assertBlockStoresExpiration("gpt-5", GPT5_KEY, BLOCK_DURATION);
    }

    @Test
    @DisplayName("차단 저장: duration이 null이면 저장하지 않는다")
    void blockDoesNotWriteWhenDurationIsNull() {
        assertBlockDoesNotWrite(null);
    }

    @Test
    @DisplayName("차단 저장: duration이 0이면 저장하지 않는다")
    void blockDoesNotWriteWhenDurationIsZero() {
        assertBlockDoesNotWrite(Duration.ZERO);
    }

    @Test
    @DisplayName("차단 저장: duration이 음수이면 저장하지 않는다")
    void blockDoesNotWriteWhenDurationIsNegative() {
        assertBlockDoesNotWrite(Duration.ofSeconds(-1));
    }

    private void assertBlockStoresExpiration(String provider, String key, Duration duration) {
        Instant before = Instant.now();

        blockStore.block(provider, duration);

        Instant after = Instant.now();
        ArgumentCaptor<String> expirationCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(key);
        verify(valueOperations).set(eq(key), expirationCaptor.capture(), eq(duration));
        Instant expiration = Instant.parse(expirationCaptor.getValue());
        assertThat(expiration).isBetween(before.plus(duration), after.plus(duration));
        assertThat(expirationCaptor.getValue()).isEqualTo(expiration.toString());
        verifyNoMoreInteractions(valueOperations);
    }

    private void assertBlockDoesNotWrite(Duration duration) {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        blockStore.block("gpt-5", duration);

        verify(valueOperations, never()).set(any(), any(), org.mockito.ArgumentMatchers.<Duration>any());
    }
}
