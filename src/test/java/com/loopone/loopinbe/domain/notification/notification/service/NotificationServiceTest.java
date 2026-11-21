package com.loopone.loopinbe.domain.notification.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmTokenService;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.dto.req.NotificationRequest;
import com.loopone.loopinbe.domain.notification.notification.dto.res.NotificationResponse;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.notification.notification.mapper.NotificationMapper;
import com.loopone.loopinbe.domain.notification.notification.mapper.NotificationMapperImpl;
import com.loopone.loopinbe.domain.notification.notification.repository.NotificationRepository;
import com.loopone.loopinbe.domain.notification.notification.serviceImpl.NotificationServiceImpl;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.domain.notification.setting.service.NotificationSettingService;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.loopone.loopinbe.global.kafka.event.fcm.FcmEventPublisher;
import com.loopone.loopinbe.support.TestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;

@DataMongoTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "testcontainers.mongo.enabled=true"
})
@Import({
        TestContainersConfig.class,
        NotificationServiceImpl.class,
        NotificationMapperImpl.class,
        NotificationServiceTest.TestConfig.class
})
class NotificationServiceTest {

    // ===== Real Repositories =====
    @Autowired NotificationRepository notificationRepository;

    // ===== SUT =====
    @Autowired NotificationServiceImpl notificationService;

    @Autowired ObjectMapper objectMapper;
    @Autowired NotificationMapper notificationMapper;

    // ===== External boundaries (mock) =====
    @MockitoBean FcmTokenService fcmTokenService;
    @MockitoBean FcmEventPublisher fcmEventPublisher;
    @MockitoBean NotificationSettingService notificationSettingService;

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
    }

    @BeforeEach
    void resetMocks() {
        clearInvocations(fcmEventPublisher, fcmTokenService, notificationSettingService);
        // 기본값: 모든 알림 활성(설정 없음과 동일). 억제 테스트에서만 개별 override.
        given(notificationSettingService.isEnabled(anyLong(), any())).willReturn(true);
    }

    // ===== Helpers =====
    private Notification persistNotification(Long receiverId, String content, Notification.TargetObject targetObject) {
        Notification n = Notification.builder()
                .senderId(2L)
                .senderNickname("sender")
                .senderProfileUrl("http://img")
                .receiverId(receiverId)
                .objectId(10L)
                .title("title")
                .content(content)
                .isRead(false)
                .targetObject(targetObject)
                .build();
        return notificationRepository.save(n);
    }

    private MemberPrincipal currentUser(Long id) {
        return new MemberPrincipal(
                id,
                "jun@loop.in",
                null,
                "jun",
                "010-0000-0000",
                Member.Gender.MALE,
                java.time.LocalDate.of(2000, 1, 1),
                null,
                Member.State.NORMAL,
                Member.MemberRole.ROLE_USER,
                Member.OAuthProvider.GOOGLE,
                "provider-id"
        );
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    // =========================================================
    // createAndNotifyFromMessage
    // =========================================================
    @Nested
    class CreateAndNotifyFromMessage {

        @Test
        @DisplayName("payload의 title/content를 저장하고 pushContent로 FCM 이벤트를 발행한다")
        void success_saveAndPublish() throws Exception {
            // given
            NotificationPayload payload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 10L,
                    "팀 초대가 도착했어요",
                    "주니님이 루프인 팀에 초대했어요",
                    "주니님이\n루프인 팀에 초대했어요",
                    Notification.TargetObject.Invite
            );
            String message = objectMapper.writeValueAsString(payload);
            given(fcmTokenService.getFcmToken(1L)).willReturn("fcm-token-123");
            given(notificationSettingService.isEnabled(1L, NotificationSettingType.TEAM_INVITE)).willReturn(true);
            ArgumentCaptor<FcmMessageResponse> captor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            // when
            notificationService.createAndNotifyFromMessage(message);

            // then
            assertThat(notificationRepository.findAll()).hasSize(1);
            Notification saved = notificationRepository.findAll().get(0);
            assertThat(saved.getTitle()).isEqualTo(payload.title());
            assertThat(saved.getContent()).isEqualTo(payload.content());
            assertThat(saved.getExpireAt()).isNotNull();
            then(fcmEventPublisher).should().publishFcm(captor.capture());
            FcmMessageResponse fcm = captor.getValue();
            NotificationResponse expectedResponse = notificationMapper.toNotificationResponse(saved);
            assertThat(fcm.getTitle()).isEqualTo(payload.title());
            assertThat(fcm.getBody()).isEqualTo(objectMapper.writeValueAsString(expectedResponse));
            assertThat(fcm.getAlertBody()).isEqualTo(payload.pushContent());
        }

        @Test
        @DisplayName("팔로우 알림의 payload title로 FCM 이벤트를 발행한다")
        void publishFollowNotificationWithPayloadTitle() throws Exception {
            // given
            NotificationPayload payload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 10L,
                    "새 팔로우 요청이 있어요",
                    "주니님이 팔로우를 요청하였습니다.",
                    null,
                    Notification.TargetObject.Follow
            );
            String message = objectMapper.writeValueAsString(payload);
            given(fcmTokenService.getFcmToken(1L)).willReturn("fcm-token-123");
            ArgumentCaptor<FcmMessageResponse> captor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            // when
            notificationService.createAndNotifyFromMessage(message);

            // then
            then(fcmEventPublisher).should().publishFcm(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo(payload.title());
        }

        @Test
        @DisplayName("pushContent가 null이면 content를 FCM alertBody로 사용한다")
        void fallsBackToContentWhenPushContentIsNull() throws Exception {
            // given
            NotificationPayload payload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 10L,
                    "새 팔로우 요청이 있어요",
                    "주니님이 팔로우를 요청하였습니다.",
                    null,
                    Notification.TargetObject.Follow
            );
            String message = objectMapper.writeValueAsString(payload);
            given(fcmTokenService.getFcmToken(1L)).willReturn("fcm-token-123");
            ArgumentCaptor<FcmMessageResponse> captor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            // when
            notificationService.createAndNotifyFromMessage(message);

            // then
            then(fcmEventPublisher).should().publishFcm(captor.capture());
            assertThat(captor.getValue().getAlertBody()).isEqualTo(payload.content());
        }

        @Test
        @DisplayName("FCM 토큰이 없으면 저장만 하고 publish 하지 않는다")
        void skipPublishWhenTokenMissing() throws Exception {
            // given
            NotificationPayload payload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 10L,
                    "팀 초대가 도착했어요",
                    "주니님이 루프인 팀에 초대했어요",
                    "주니님이\n루프인 팀에 초대했어요",
                    Notification.TargetObject.Invite
            );
            String message = objectMapper.writeValueAsString(payload);
            given(fcmTokenService.getFcmToken(1L)).willReturn(null);

            // when
            notificationService.createAndNotifyFromMessage(message);

            // then
            assertThat(notificationRepository.findAll()).hasSize(1);
            then(fcmEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("실패: 잘못된 payload면 IllegalArgumentException")
        void fail_invalidPayload() {
            assertThatThrownBy(() -> notificationService.createAndNotifyFromMessage("not-json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid notification payload");
        }

        @Test
        @DisplayName("TEAM_INVITE를 끄면 저장하되 FCM은 발행하지 않는다")
        void suppressInvitePushWhenTeamInviteDisabled() throws Exception {
            // given
            NotificationPayload payload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 10L,
                    "팀 초대가 도착했어요",
                    "주니님이 루프인 팀에 초대했어요",
                    "주니님이\n루프인 팀에 초대했어요",
                    Notification.TargetObject.Invite
            );
            String message = objectMapper.writeValueAsString(payload);
            given(fcmTokenService.getFcmToken(1L)).willReturn("fcm-token-123");
            given(notificationSettingService.isEnabled(1L, NotificationSettingType.TEAM_INVITE)).willReturn(false);

            // when
            notificationService.createAndNotifyFromMessage(message);

            // then
            assertThat(notificationRepository.findAll()).hasSize(1);
            then(fcmEventPublisher).shouldHaveNoInteractions();
        }
    }

    // =========================================================
    // createChatNotification
    // =========================================================
    @Nested
    class CreateChatNotification {

        @Test
        @DisplayName("같은 수신자와 채팅방의 알림은 최신 메시지로 upsert되고 버블링된다")
        void upsertsLatestMessagePerChatRoom() throws Exception {
            // given
            NotificationPayload firstPayload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 100L,
                    "주니님이 메시지를 보냈어요",
                    "메시지A",
                    null,
                    Notification.TargetObject.Chat
            );
            NotificationPayload secondPayload = new NotificationPayload(
                    2L, "주니", "http://img", 1L, 100L,
                    "주니님이 메시지를 보냈어요",
                    "메시지B",
                    null,
                    Notification.TargetObject.Chat
            );

            // when
            notificationService.createChatNotification(firstPayload);
            Notification firstSaved = notificationRepository.findAll().get(0);
            assertThat(firstSaved.getCreatedAt()).isNotNull();
            var firstCreatedAt = firstSaved.getCreatedAt();
            Thread.sleep(5L);
            notificationService.createChatNotification(secondPayload);

            // then
            assertThat(notificationRepository.findAll()).hasSize(1);
            Notification updated = notificationRepository.findAll().get(0);
            assertThat(updated.getContent()).isEqualTo("메시지B");
            assertThat(updated.getTitle()).isEqualTo(secondPayload.title());
            assertThat(updated.getTargetObject()).isEqualTo(Notification.TargetObject.Chat);
            assertThat(updated.getIsRead()).isFalse();
            assertThat(updated.getCreatedAt()).isNotNull().isAfterOrEqualTo(firstCreatedAt);
        }
    }

    // =========================================================
    // getNotifications
    // =========================================================
    @Nested
    class GetNotifications {

        @Test
        @DisplayName("성공: receiverId 기준으로 목록을 조회한다")
        void success_get() {
            // given
            persistNotification(1L, "a", Notification.TargetObject.Follow);
            persistNotification(1L, "b", Notification.TargetObject.Invite);
            persistNotification(2L, "c", Notification.TargetObject.Invite);

            // when
            var result = notificationService.getNotifications(null, 20, currentUser(1L));

            // then
            assertThat(result.getContent()).hasSize(2);
        }

        @Test
        @DisplayName("실패: 페이지 사이즈가 최대값을 초과하면 PAGE_REQUEST_FAIL")
        void fail_pageSizeTooLarge() {
            assertThatThrownBy(() -> notificationService.getNotifications(null, 21, currentUser(1L)))
                    .isInstanceOf(ServiceException.class)
                    .extracting("returnCode").isEqualTo(ReturnCode.PAGE_REQUEST_FAIL);
        }
    }

    // =========================================================
    // markAsRead
    // =========================================================
    @Nested
    class MarkAsRead {

        @Test
        @DisplayName("성공: 본인 알림은 읽음 처리된다")
        void success_markAsRead() {
            // given
            Notification n1 = persistNotification(1L, "a", Notification.TargetObject.Follow);
            Notification n2 = persistNotification(1L, "b", Notification.TargetObject.Invite);

            // when
            notificationService.markAsRead(new NotificationRequest(List.of(n1.getId(), n2.getId())), currentUser(1L));

            // then
            Notification reloaded1 = notificationRepository.findById(n1.getId()).orElseThrow();
            Notification reloaded2 = notificationRepository.findById(n2.getId()).orElseThrow();
            assertThat(reloaded1.getIsRead()).isTrue();
            assertThat(reloaded2.getIsRead()).isTrue();
        }

        @Test
        @DisplayName("실패: 다른 사용자 알림이 섞이면 NOTIFICATION_NOT_FOUND")
        void fail_whenNotOwned() {
            // given
            Notification mine = persistNotification(1L, "a", Notification.TargetObject.Follow);
            Notification others = persistNotification(2L, "b", Notification.TargetObject.Invite);
            NotificationRequest req = new NotificationRequest(List.of(mine.getId(), others.getId()));

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(req, currentUser(1L)))
                    .isInstanceOf(ServiceException.class)
                    .extracting("returnCode").isEqualTo(ReturnCode.NOTIFICATION_NOT_FOUND);
        }
    }
}
