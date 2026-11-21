package com.loopone.loopinbe.global.kafka.event.fcm.service;

import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmIdempotencyService;
import com.loopone.loopinbe.domain.fcm.service.FcmService;
import com.loopone.loopinbe.global.kafka.event.fcm.serviceImpl.FcmEventServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class FcmEventServiceTest {

    @Mock
    FcmService fcmService;

    @Mock
    FcmIdempotencyService fcmIdempotencyService;

    @InjectMocks
    FcmEventServiceImpl fcmEventService;

    // ===== Helpers =====
    private FcmMessageResponse message(String eventId, String targetToken) {
        return FcmMessageResponse.builder()
                .eventId(eventId)
                .targetToken(targetToken)
                .receiverId(1L)
                .title("title")
                .body("body")
                .build();
    }

    @Nested
    @DisplayName("processFcmEvent")
    class ProcessFcmEvent {

        @Test
        @DisplayName("성공: 정상 발송 → sendMessageTo + markSuccess 호출")
        void success_sendAndMark() {
            // given
            FcmMessageResponse msg = message("evt-1", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-1")).willReturn(Optional.of("idem-token"));

            // when
            fcmEventService.processFcmEvent(msg);

            // then
            then(fcmService).should().sendMessageTo(msg);
            then(fcmIdempotencyService).should().markSuccess("evt-1", "idem-token");
            then(fcmIdempotencyService).should(never()).release("evt-1", "idem-token");
        }

        @Test
        @DisplayName("성공: 중복 이벤트 스킵 (tryAcquire empty) → sendMessageTo 호출 안 됨")
        void success_duplicateSkipped() {
            // given
            FcmMessageResponse msg = message("evt-dup", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-dup")).willReturn(Optional.empty());

            // when
            fcmEventService.processFcmEvent(msg);

            // then
            then(fcmService).should(never()).sendMessageTo(msg);
        }

        @Test
        @DisplayName("성공: Redis 장애 시 fail-open — 멱등성 없이 발송")
        void success_redisUnavailable_sendWithoutIdempotency() {
            // given
            FcmMessageResponse msg = message("evt-redis-fail", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-redis-fail"))
                    .willThrow(new RuntimeException("Redis connection refused"));

            // when
            fcmEventService.processFcmEvent(msg);

            // then
            then(fcmService).should().sendMessageTo(msg);
            then(fcmIdempotencyService).should(never()).markSuccess(any(), any());
        }

        @Test
        @DisplayName("실패: Redis 장애 + FCM 발송 실패 → release 호출 없이 예외 재throw")
        void fail_redisUnavailable_sendFails_noRelease() {
            // given
            FcmMessageResponse msg = message("evt-redis-send-fail", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-redis-send-fail"))
                    .willThrow(new RuntimeException("Redis connection refused"));
            RuntimeException boom = new RuntimeException("FCM send failed");
            willThrow(boom).given(fcmService).sendMessageTo(msg);

            // when & then
            assertThatThrownBy(() -> fcmEventService.processFcmEvent(msg))
                    .isSameAs(boom);

            then(fcmIdempotencyService).should(never()).release("evt-redis-send-fail", null);
            then(fcmIdempotencyService).should(never()).markSuccess("evt-redis-send-fail", null);
        }

        @Test
        @DisplayName("성공: markSuccess 예외 → 예외 삼킴 (발송 성공이므로 재시도 불필요)")
        void success_markSuccessThrows_swallowed() {
            // given
            FcmMessageResponse msg = message("evt-mark-fail", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-mark-fail")).willReturn(Optional.of("idem-token"));
            willThrow(new RuntimeException("Redis write failed"))
                    .given(fcmIdempotencyService).markSuccess("evt-mark-fail", "idem-token");

            // when & then
            assertThatCode(() -> fcmEventService.processFcmEvent(msg))
                    .doesNotThrowAnyException();

            then(fcmService).should().sendMessageTo(msg);
            then(fcmIdempotencyService).should(never()).release("evt-mark-fail", "idem-token");
        }

        @Test
        @DisplayName("실패: eventId가 blank → IllegalArgumentException")
        void fail_blankEventId() {
            // given
            FcmMessageResponse msg = message("", "token-1");

            // when & then
            assertThatThrownBy(() -> fcmEventService.processFcmEvent(msg))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("FCM eventId is blank");
        }

        @Test
        @DisplayName("실패: targetToken이 blank → IllegalArgumentException")
        void fail_blankTargetToken() {
            // given
            FcmMessageResponse msg = message("evt-2", "");

            // when & then
            assertThatThrownBy(() -> fcmEventService.processFcmEvent(msg))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("FCM targetToken is blank");
        }

        @Test
        @DisplayName("실패: sendMessageTo 예외 + release 예외 → safeRelease가 삼키고 원래 예외만 전파")
        void fail_sendThrows_releaseAlsoThrows_originalExceptionPropagates() {
            // given
            FcmMessageResponse msg = message("evt-double-fail", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-double-fail")).willReturn(Optional.of("idem-token"));
            RuntimeException fcmBoom = new RuntimeException("FCM failed");
            willThrow(fcmBoom).given(fcmService).sendMessageTo(msg);
            willThrow(new RuntimeException("Redis down"))
                    .given(fcmIdempotencyService).release("evt-double-fail", "idem-token");

            // when & then
            assertThatThrownBy(() -> fcmEventService.processFcmEvent(msg))
                    .isSameAs(fcmBoom);

            then(fcmIdempotencyService).should(never()).markSuccess("evt-double-fail", "idem-token");
        }

        @Test
        @DisplayName("실패: sendMessageTo 예외 → release 호출 후 예외 재throw")
        void fail_sendThrows_thenRelease() {
            // given
            FcmMessageResponse msg = message("evt-err", "token-1");
            given(fcmIdempotencyService.tryAcquire("evt-err")).willReturn(Optional.of("idem-token"));
            RuntimeException boom = new RuntimeException("FCM send failed");
            willThrow(boom).given(fcmService).sendMessageTo(msg);

            // when & then
            assertThatThrownBy(() -> fcmEventService.processFcmEvent(msg))
                    .isSameAs(boom);

            then(fcmIdempotencyService).should().release("evt-err", "idem-token");
            then(fcmIdempotencyService).should(never()).markSuccess("evt-err", "idem-token");
        }
    }
}
