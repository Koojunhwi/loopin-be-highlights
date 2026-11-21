package com.loopone.loopinbe.domain.fcm.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.serviceImpl.FcmServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    private final FcmTokenService fcmTokenService = mock(FcmTokenService.class);
    private final FcmServiceImpl fcmService = new FcmServiceImpl(fcmTokenService);

    @Nested
    @DisplayName("sendMessageTo")
    class SendMessageTo {

        @Test
        @DisplayName("성공: FirebaseMessaging에 올바른 Message가 전달된다")
        void sendMessage_success() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            when(firebaseMessaging.send(any(Message.class))).thenReturn("ok");

            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-1")
                    .receiverId(100L)
                    .targetToken("token-123")
                    .title("title-1")
                    .body("body-1")
                    .alertBody("alert-body-1")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when
                fcmService.sendMessageTo(req);

                // then
                ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
                verify(firebaseMessaging).send(captor.capture());
                Message msg = captor.getValue();
                assertThat(readToken(msg)).isEqualTo("token-123");
                assertThat(readData(msg)).isEqualTo(Map.of("title", "title-1", "body", "body-1", "alertBody", "alert-body-1"));
            }
        }

        @Test
        @DisplayName("collapseKey를 Android와 APNs 설정에 모두 반영한다")
        void sendMessage_appliesCollapseKey() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            when(firebaseMessaging.send(any(Message.class))).thenReturn("ok");
            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-collapse")
                    .receiverId(100L)
                    .targetToken("token-123")
                    .title("주니")
                    .body("body")
                    .alertBody("hello")
                    .collapseKey("chat-1")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when
                fcmService.sendMessageTo(req);

                // then
                ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
                verify(firebaseMessaging).send(captor.capture());
                Message message = captor.getValue();
                AndroidConfig androidConfig = (AndroidConfig) readField(message, "androidConfig");
                ApnsConfig apnsConfig = (ApnsConfig) readField(message, "apnsConfig");
                assertThat(readField(androidConfig, "collapseKey")).isEqualTo("chat-1");
                assertThat(readHeaders(apnsConfig)).containsEntry("apns-collapse-id", "chat-1");
            }
        }

        @Test
        @DisplayName("title이 null이면 data에서 title 키를 생략한다")
        void sendMessage_omitsNullTitleFromData() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            when(firebaseMessaging.send(any(Message.class))).thenReturn("ok");
            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-null-title")
                    .receiverId(100L)
                    .targetToken("token-123")
                    .title(null)
                    .body("body")
                    .alertBody("hello")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when
                fcmService.sendMessageTo(req);

                // then
                ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
                verify(firebaseMessaging).send(captor.capture());
                assertThat(readData(captor.getValue())).doesNotContainKey("title");
            }
        }

        @Test
        @DisplayName("UNREGISTERED 에러: 토큰을 삭제하고 예외를 전파하지 않는다")
        void sendMessage_unregistered_deletesTokenAndDoesNotThrow() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
            when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);

            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-2")
                    .receiverId(200L)
                    .targetToken("token-invalid")
                    .title("title")
                    .body("body")
                    .alertBody("alert")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when / then
                assertThatCode(() -> fcmService.sendMessageTo(req)).doesNotThrowAnyException();
                verify(fcmTokenService).deleteFcmToken(200L);
            }
        }

        @Test
        @DisplayName("INVALID_ARGUMENT 에러: 토큰을 삭제하고 예외를 전파하지 않는다")
        void sendMessage_invalidArgument_deletesTokenAndDoesNotThrow() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
            when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INVALID_ARGUMENT);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);

            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-3")
                    .receiverId(300L)
                    .targetToken("token-bad")
                    .title("title")
                    .body("body")
                    .alertBody("alert")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when / then
                assertThatCode(() -> fcmService.sendMessageTo(req)).doesNotThrowAnyException();
                verify(fcmTokenService).deleteFcmToken(300L);
            }
        }

        @Test
        @DisplayName("UNREGISTERED 에러 + receiverId null: 토큰 삭제를 호출하지 않는다")
        void sendMessage_unregistered_nullReceiverId_doesNotDeleteToken() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
            when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);

            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-4")
                    .targetToken("token-no-receiver")
                    .title("title")
                    .body("body")
                    .alertBody("alert")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when / then
                assertThatCode(() -> fcmService.sendMessageTo(req)).doesNotThrowAnyException();
                verify(fcmTokenService, never()).deleteFcmToken(any());
            }
        }

        @Test
        @DisplayName("UNAVAILABLE 에러: RuntimeException을 전파한다 (Kafka 재시도)")
        void sendMessage_unavailable_throwsRuntimeException() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
            when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);

            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-5")
                    .receiverId(500L)
                    .targetToken("token-retry")
                    .title("title")
                    .body("body")
                    .alertBody("alert")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when / then
                assertThatThrownBy(() -> fcmService.sendMessageTo(req))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("FCM send failed, retriable")
                        .hasCauseInstanceOf(FirebaseMessagingException.class);
                verify(fcmTokenService, never()).deleteFcmToken(any());
            }
        }

        @Test
        @DisplayName("INTERNAL 에러: RuntimeException을 전파한다 (Kafka 재시도)")
        void sendMessage_internal_throwsRuntimeException() throws Exception {
            // given
            FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
            FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
            when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INTERNAL);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);

            FcmMessageResponse req = FcmMessageResponse.builder()
                    .eventId("evt-6")
                    .receiverId(600L)
                    .targetToken("token-retry-2")
                    .title("title")
                    .body("body")
                    .alertBody("alert")
                    .build();

            try (MockedStatic<FirebaseMessaging> mocked = mockStatic(FirebaseMessaging.class)) {
                mocked.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

                // when / then
                assertThatThrownBy(() -> fcmService.sendMessageTo(req))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("FCM send failed, retriable");
                verify(fcmTokenService, never()).deleteFcmToken(any());
            }
        }
    }

    private String readToken(Message message) {
        try {
            var field = Message.class.getDeclaredField("token");
            field.setAccessible(true);
            Object value = field.get(message);
            return value != null ? value.toString() : null;
        } catch (ReflectiveOperationException e) {
            fail("Failed to access token field via reflection", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readData(Message message) {
        try {
            var field = Message.class.getDeclaredField("data");
            field.setAccessible(true);
            Object value = field.get(message);
            return (Map<String, String>) value;
        } catch (ReflectiveOperationException e) {
            fail("Failed to access data field via reflection", e);
            return Map.of();
        }
    }

    private Object readField(Object target, String fieldName) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            fail("Failed to access " + fieldName + " field via reflection", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readHeaders(ApnsConfig apnsConfig) {
        return (Map<String, String>) readField(apnsConfig, "headers");
    }
}
