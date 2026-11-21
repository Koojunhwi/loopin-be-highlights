package com.loopone.loopinbe.global.kafka.event.chatMessage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatMessagePayload;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.TeamChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType;
import com.loopone.loopinbe.domain.chat.chatMessage.mapper.ChatMessageMapper;
import com.loopone.loopinbe.domain.chat.chatMessage.service.ChatMessageService;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomMemberService;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomService;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmTokenService;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.notification.notification.service.NotificationService;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.domain.notification.setting.service.NotificationSettingService;
import com.loopone.loopinbe.global.kafka.event.chatMessage.serviceImpl.ChatMessageEventServiceImpl;
import com.loopone.loopinbe.global.kafka.event.fcm.FcmEventPublisher;
import com.loopone.loopinbe.global.webSocket.handler.ChatWebSocketHandler;
import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageEventService 단위 테스트")
class ChatMessageEventServiceTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Mock
    private ChatWebSocketHandler chatWebSocketHandler;
    @Mock
    private ChatRoomService chatRoomService;
    @Mock
    private ChatRoomMemberService chatRoomMemberService;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private FcmTokenService fcmTokenService;
    @Mock
    private FcmEventPublisher fcmEventPublisher;
    @Mock
    private NotificationSettingService notificationSettingService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ChatMessageEventServiceImpl chatMessageEventService;

    // ===== 공통 fixture =====
    private static final Long CHAT_ROOM_ID = 1L;
    private static final Long MEMBER_ID = 100L;
    private static final UUID CLIENT_MESSAGE_ID = UUID.randomUUID();
    private static final String CONTENT = "hello";

    private ChatWebSocketPayload messageEvent(Long memberId, UUID clientMessageId, String content) {
        return ChatWebSocketPayload.builder()
                .messageType(MessageType.MESSAGE)
                .chatRoomId(CHAT_ROOM_ID)
                .memberId(memberId)
                .clientMessageId(clientMessageId)
                .teamChatMessageResponse(content != null
                        ? TeamChatMessageResponse.builder().content(content).build()
                        : null)
                .build();
    }

    @Nested
    @DisplayName("processMessageEvent")
    class ProcessMessageEvent {

        @Test
        @DisplayName("성공: processInbound 호출 후 TeamChatMessageResponse 매핑하여 broadcastToRoom")
        void success() throws Exception {
            // given
            ChatWebSocketPayload event = messageEvent(MEMBER_ID, CLIENT_MESSAGE_ID, CONTENT);

            ChatMessagePayload saved = new ChatMessagePayload(
                    "u:" + CLIENT_MESSAGE_ID, CLIENT_MESSAGE_ID, CHAT_ROOM_ID, MEMBER_ID, CONTENT,
                    null, null, null, null, ChatMessage.AuthorType.USER, false, false,
                    Instant.now(), Instant.now()
            );
            given(chatMessageService.processInbound(any(ChatMessagePayload.class))).willReturn(saved);

            Member member = Member.builder().id(MEMBER_ID).nickname("tester").build();
            Map<Long, Member> memberMap = Map.of(MEMBER_ID, member);
            given(chatMessageMapper.loadMembersFromPayload(anyList())).willReturn(memberMap);

            TeamChatMessageResponse resp = TeamChatMessageResponse.builder()
                    .id("u:" + CLIENT_MESSAGE_ID).memberId(MEMBER_ID).content(CONTENT).build();
            given(chatMessageMapper.toTeamChatMessageResponse(eq(saved), eq(memberMap), eq(MEMBER_ID)))
                    .willReturn(resp);
            given(chatRoomMemberService.getOtherMemberIds(anyLong(), anyLong())).willReturn(List.of());

            // when
            chatMessageEventService.processMessageEvent(event);

            // then
            then(chatMessageService).should().processInbound(any(ChatMessagePayload.class));
            then(chatWebSocketHandler).should().broadcastToRoom(eq(CHAT_ROOM_ID), anyString());
        }

        @Test
        @DisplayName("실패: memberId가 null이면 IllegalArgumentException")
        void failWhenMemberIdNull() {
            ChatWebSocketPayload event = messageEvent(null, CLIENT_MESSAGE_ID, CONTENT);

            assertThatThrownBy(() -> chatMessageEventService.processMessageEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: clientMessageId가 null이면 IllegalArgumentException")
        void failWhenClientMessageIdNull() {
            ChatWebSocketPayload event = messageEvent(MEMBER_ID, null, CONTENT);

            assertThatThrownBy(() -> chatMessageEventService.processMessageEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: content가 null이면 IllegalArgumentException")
        void failWhenContentNull() {
            ChatWebSocketPayload event = messageEvent(MEMBER_ID, CLIENT_MESSAGE_ID, null);

            assertThatThrownBy(() -> chatMessageEventService.processMessageEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: content가 blank이면 IllegalArgumentException")
        void failWhenContentBlank() {
            ChatWebSocketPayload event = messageEvent(MEMBER_ID, CLIENT_MESSAGE_ID, "   ");

            assertThatThrownBy(() -> chatMessageEventService.processMessageEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("채팅 푸시 fan-out")
    class ChatPushFanOut {

        @Test
        @DisplayName("전체 수신자에게 채팅 알림을 저장하고 푸시를 발송한다")
        void publishesToAllMembersExceptSender() {
            UUID messageId = UUID.randomUUID();
            ChatWebSocketPayload event = prepareMessageEvent(messageId, CONTENT);
            given(chatRoomMemberService.getOtherMemberIds(CHAT_ROOM_ID, MEMBER_ID))
                    .willReturn(List.of(200L, 300L));
            given(notificationSettingService.filterEnabled(anyList(), eq(NotificationSettingType.TEAM_CHAT)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(fcmTokenService.getFcmToken(200L)).willReturn("tok200");
            given(fcmTokenService.getFcmToken(300L)).willReturn("tok300");
            ArgumentCaptor<NotificationPayload> notificationCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
            ArgumentCaptor<FcmMessageResponse> fcmCaptor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            chatMessageEventService.processMessageEvent(event);

            then(notificationService).should(times(2)).createChatNotification(notificationCaptor.capture());
            assertThat(notificationCaptor.getAllValues())
                    .extracting(NotificationPayload::receiverId)
                    .containsExactlyInAnyOrder(200L, 300L);
            assertThat(notificationCaptor.getAllValues()).allSatisfy(payload -> {
                assertThat(payload.senderId()).isEqualTo(MEMBER_ID);
                assertThat(payload.senderNickname()).isEqualTo("주니");
                assertThat(payload.objectId()).isEqualTo(CHAT_ROOM_ID);
                assertThat(payload.title()).isEqualTo("주니님이 메시지를 보냈어요");
                assertThat(payload.content()).isEqualTo(CONTENT);
                assertThat(payload.targetObject()).isEqualTo(Notification.TargetObject.Chat);
            });

            then(fcmEventPublisher).should(times(2)).publishFcm(fcmCaptor.capture());
            assertThat(fcmCaptor.getAllValues())
                    .extracting(FcmMessageResponse::getReceiverId)
                    .containsExactlyInAnyOrder(200L, 300L);
            assertThat(fcmCaptor.getAllValues()).allSatisfy(message -> {
                assertThat(message.getTitle()).isEqualTo("주니");
                assertThat(message.getAlertBody()).isEqualTo(CONTENT);
                assertThat(message.getCollapseKey()).isEqualTo("chat-" + CHAT_ROOM_ID);
                assertThat(message.getEventId()).startsWith("chat:");
                assertThat(message.getEventId()).endsWith(":" + message.getReceiverId());
            });
        }

        @Test
        @DisplayName("FCM 토큰이 없는 멤버는 건너뛴다")
        void skipsMemberWithoutFcmToken() {
            UUID messageId = UUID.randomUUID();
            ChatWebSocketPayload event = prepareMessageEvent(messageId, CONTENT);
            given(chatRoomMemberService.getOtherMemberIds(CHAT_ROOM_ID, MEMBER_ID))
                    .willReturn(List.of(200L, 300L));
            given(notificationSettingService.filterEnabled(anyList(), eq(NotificationSettingType.TEAM_CHAT)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(fcmTokenService.getFcmToken(200L)).willReturn("tok200");
            given(fcmTokenService.getFcmToken(300L)).willReturn(null);
            ArgumentCaptor<NotificationPayload> notificationCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
            ArgumentCaptor<FcmMessageResponse> fcmCaptor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            chatMessageEventService.processMessageEvent(event);

            then(notificationService).should(times(2)).createChatNotification(notificationCaptor.capture());
            assertThat(notificationCaptor.getAllValues())
                    .extracting(NotificationPayload::receiverId)
                    .containsExactlyInAnyOrder(200L, 300L);
            then(fcmEventPublisher).should(times(1)).publishFcm(fcmCaptor.capture());
            assertThat(fcmCaptor.getValue().getReceiverId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("20자를 초과한 메시지 미리보기는 앞 20자와 말줄임표로 만든다")
        void truncatesLongMessagePreview() {
            String content = "가".repeat(25);
            UUID messageId = UUID.randomUUID();
            ChatWebSocketPayload event = prepareMessageEvent(messageId, content);
            given(chatRoomMemberService.getOtherMemberIds(CHAT_ROOM_ID, MEMBER_ID))
                    .willReturn(List.of(200L));
            given(notificationSettingService.filterEnabled(anyList(), eq(NotificationSettingType.TEAM_CHAT)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(fcmTokenService.getFcmToken(200L)).willReturn("tok200");
            ArgumentCaptor<NotificationPayload> notificationCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
            ArgumentCaptor<FcmMessageResponse> fcmCaptor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            chatMessageEventService.processMessageEvent(event);

            then(notificationService).should().createChatNotification(notificationCaptor.capture());
            assertThat(notificationCaptor.getValue().content()).hasSize(21).endsWith("…");
            then(fcmEventPublisher).should().publishFcm(fcmCaptor.capture());
            assertThat(fcmCaptor.getValue().getAlertBody()).hasSize(21).endsWith("…");
            assertThat(fcmCaptor.getValue().getCollapseKey()).isEqualTo("chat-" + CHAT_ROOM_ID);
        }

        @Test
        @DisplayName("발신자 외 다른 멤버가 없으면 저장과 푸시를 모두 생략한다")
        void doesNotPublishWhenNoOtherMembers() {
            UUID messageId = UUID.randomUUID();
            ChatWebSocketPayload event = prepareMessageEvent(messageId, CONTENT);
            given(chatRoomMemberService.getOtherMemberIds(CHAT_ROOM_ID, MEMBER_ID))
                    .willReturn(List.of());

            chatMessageEventService.processMessageEvent(event);

            then(notificationService).should(never()).createChatNotification(any(NotificationPayload.class));
            then(fcmEventPublisher).should(never()).publishFcm(any(FcmMessageResponse.class));
        }

        @Test
        @DisplayName("TEAM_CHAT을 끈 수신자도 저장하되 푸시는 활성 수신자에게만 발송한다")
        void excludesMembersWithTeamChatDisabled() {
            UUID messageId = UUID.randomUUID();
            ChatWebSocketPayload event = prepareMessageEvent(messageId, CONTENT);
            given(chatRoomMemberService.getOtherMemberIds(CHAT_ROOM_ID, MEMBER_ID))
                    .willReturn(List.of(200L, 300L));
            // 300L은 TEAM_CHAT 비활성 → filterEnabled가 200L만 남긴다
            given(notificationSettingService.filterEnabled(anyList(), eq(NotificationSettingType.TEAM_CHAT)))
                    .willReturn(List.of(200L));
            given(fcmTokenService.getFcmToken(200L)).willReturn("tok200");
            ArgumentCaptor<NotificationPayload> notificationCaptor = ArgumentCaptor.forClass(NotificationPayload.class);
            ArgumentCaptor<FcmMessageResponse> fcmCaptor = ArgumentCaptor.forClass(FcmMessageResponse.class);

            chatMessageEventService.processMessageEvent(event);

            then(notificationService).should(times(2)).createChatNotification(notificationCaptor.capture());
            assertThat(notificationCaptor.getAllValues())
                    .extracting(NotificationPayload::receiverId)
                    .containsExactlyInAnyOrder(200L, 300L);
            then(fcmEventPublisher).should(times(1)).publishFcm(fcmCaptor.capture());
            assertThat(fcmCaptor.getValue().getReceiverId()).isEqualTo(200L);
        }

        private ChatWebSocketPayload prepareMessageEvent(UUID messageId, String content) {
            ChatWebSocketPayload event = messageEvent(MEMBER_ID, messageId, content);
            ChatMessagePayload saved = new ChatMessagePayload(
                    "u:" + messageId, messageId, CHAT_ROOM_ID, MEMBER_ID, content,
                    null, null, null, null, ChatMessage.AuthorType.USER, false, false,
                    Instant.now(), Instant.now()
            );
            given(chatMessageService.processInbound(any(ChatMessagePayload.class))).willReturn(saved);

            Member sender = Member.builder().id(MEMBER_ID).nickname("주니").build();
            Map<Long, Member> memberMap = Map.of(MEMBER_ID, sender);
            given(chatMessageMapper.loadMembersFromPayload(anyList())).willReturn(memberMap);

            TeamChatMessageResponse response = TeamChatMessageResponse.builder()
                    .id("u:" + messageId).memberId(MEMBER_ID).content(content).build();
            given(chatMessageMapper.toTeamChatMessageResponse(eq(saved), eq(memberMap), eq(MEMBER_ID)))
                    .willReturn(response);
            return event;
        }
    }

    @Nested
    @DisplayName("processReadUpToEvent")
    class ProcessReadUpToEvent {

        @Test
        @DisplayName("성공: updateLastReadAt 호출 후 broadcastToRoom")
        void success() throws Exception {
            // given
            Instant lastReadAt = Instant.now();
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.READ_UP_TO)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .lastReadAt(lastReadAt)
                    .build();

            Instant updated = lastReadAt.plusSeconds(1);
            given(chatRoomMemberService.updateLastReadAt(CHAT_ROOM_ID, MEMBER_ID, lastReadAt))
                    .willReturn(updated);

            // when
            chatMessageEventService.processReadUpToEvent(event);

            // then
            then(chatRoomMemberService).should().updateLastReadAt(CHAT_ROOM_ID, MEMBER_ID, lastReadAt);
            then(chatWebSocketHandler).should().broadcastToRoom(eq(CHAT_ROOM_ID), anyString());
        }

        @Test
        @DisplayName("실패: memberId가 null이면 IllegalArgumentException")
        void failWhenMemberIdNull() {
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.READ_UP_TO)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(null)
                    .lastReadAt(Instant.now())
                    .build();

            assertThatThrownBy(() -> chatMessageEventService.processReadUpToEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: lastReadAt가 null이면 IllegalArgumentException")
        void failWhenLastReadAtNull() {
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.READ_UP_TO)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .lastReadAt(null)
                    .build();

            assertThatThrownBy(() -> chatMessageEventService.processReadUpToEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("processSetNoticeEvent")
    class ProcessSetNoticeEvent {

        @Test
        @DisplayName("성공: updateNoticeMessageContent 호출 후 broadcastToRoom")
        void success() throws Exception {
            // given
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.SET_NOTICE)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .noticeMessageId("msg-123")
                    .noticeMessageContent("공지 내용")
                    .build();

            given(chatRoomService.updateNoticeMessageContent(CHAT_ROOM_ID, "공지 내용"))
                    .willReturn(true);

            // when
            chatMessageEventService.processSetNoticeEvent(event);

            // then
            then(chatRoomService).should().updateNoticeMessageContent(CHAT_ROOM_ID, "공지 내용");
            then(chatWebSocketHandler).should().broadcastToRoom(eq(CHAT_ROOM_ID), anyString());
        }

        @Test
        @DisplayName("실패: noticeMessageId가 null이면 IllegalArgumentException")
        void failWhenNoticeMessageIdNull() {
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.SET_NOTICE)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .noticeMessageId(null)
                    .noticeMessageContent("공지")
                    .build();

            assertThatThrownBy(() -> chatMessageEventService.processSetNoticeEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: chatroom not found 시 (updateNoticeMessageContent가 false 반환) broadcastToRoom 호출하지 않음")
        void failWhenChatroomNotFound() {
            // given
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.SET_NOTICE)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .noticeMessageId("msg-123")
                    .noticeMessageContent("공지")
                    .build();

            given(chatRoomService.updateNoticeMessageContent(CHAT_ROOM_ID, "공지"))
                    .willReturn(false);

            // when
            chatMessageEventService.processSetNoticeEvent(event);

            // then
            then(chatWebSocketHandler).should(never()).broadcastToRoom(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("processDeleteEvent")
    class ProcessDeleteEvent {

        @Test
        @DisplayName("성공: deleteChatMessage 호출 후 broadcastToRoom")
        void success() throws Exception {
            // given
            String deleteId = "msg-456";
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.DELETE)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .deleteId(deleteId)
                    .build();

            // when
            chatMessageEventService.processDeleteEvent(event);

            // then
            then(chatMessageService).should().deleteChatMessage(deleteId, MEMBER_ID);
            then(chatWebSocketHandler).should().broadcastToRoom(eq(CHAT_ROOM_ID), anyString());
        }

        @Test
        @DisplayName("실패: memberId가 null이면 IllegalArgumentException")
        void failWhenMemberIdNull() {
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.DELETE)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(null)
                    .deleteId("msg-456")
                    .build();

            assertThatThrownBy(() -> chatMessageEventService.processDeleteEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: deleteId가 null이면 IllegalArgumentException")
        void failWhenDeleteIdNull() {
            ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                    .messageType(MessageType.DELETE)
                    .chatRoomId(CHAT_ROOM_ID)
                    .memberId(MEMBER_ID)
                    .deleteId(null)
                    .build();

            assertThatThrownBy(() -> chatMessageEventService.processDeleteEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
