package com.loopone.loopinbe.global.webSocket.service;

import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.TeamChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType;
import com.loopone.loopinbe.global.kafka.event.chatMessage.ChatMessageEventPublisher;
import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;
import com.loopone.loopinbe.global.webSocket.serviceImpl.ChatWebSocketServiceImpl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketServiceTest {

    @Mock
    ChatMessageEventPublisher chatMessageEventPublisher;

    @InjectMocks
    ChatWebSocketServiceImpl chatWebSocketService;

    private static final Long CHAT_ROOM_ID = 1L;
    private static final Long MEMBER_ID = 42L;

    @Nested
    class HandleMessage {

        @Test
        void success_validMessagePayload_publishesEvent() {
            UUID clientMessageId = UUID.randomUUID();
            String content = "Hello, World!";
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.MESSAGE)
                    .clientMessageId(clientMessageId)
                    .teamChatMessageResponse(TeamChatMessageResponse.builder()
                            .content(content)
                            .build())
                    .build();

            chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload);

            ArgumentCaptor<ChatWebSocketPayload> captor = ArgumentCaptor.forClass(ChatWebSocketPayload.class);
            verify(chatMessageEventPublisher).publishWsEvent(captor.capture());

            ChatWebSocketPayload event = captor.getValue();
            assertThat(event.getMessageType()).isEqualTo(MessageType.MESSAGE);
            assertThat(event.getChatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(event.getClientMessageId()).isEqualTo(clientMessageId);
            assertThat(event.getTeamChatMessageResponse()).isNotNull();
            assertThat(event.getTeamChatMessageResponse().getContent()).isEqualTo(content);
        }

        @Test
        void fail_clientMessageIdIsNull_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.MESSAGE)
                    .clientMessageId(null)
                    .teamChatMessageResponse(TeamChatMessageResponse.builder()
                            .content("some content")
                            .build())
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }

        @Test
        void fail_teamChatMessageResponseIsNull_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.MESSAGE)
                    .clientMessageId(UUID.randomUUID())
                    .teamChatMessageResponse(null)
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }

        @Test
        void fail_contentIsBlank_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.MESSAGE)
                    .clientMessageId(UUID.randomUUID())
                    .teamChatMessageResponse(TeamChatMessageResponse.builder()
                            .content("   ")
                            .build())
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }
    }

    @Nested
    class HandleReadUpTo {

        @Test
        void success_validReadUpToPayload_publishesEvent() {
            Instant lastReadAt = Instant.now();
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.READ_UP_TO)
                    .lastReadAt(lastReadAt)
                    .build();

            chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload);

            ArgumentCaptor<ChatWebSocketPayload> captor = ArgumentCaptor.forClass(ChatWebSocketPayload.class);
            verify(chatMessageEventPublisher).publishWsEvent(captor.capture());

            ChatWebSocketPayload event = captor.getValue();
            assertThat(event.getMessageType()).isEqualTo(MessageType.READ_UP_TO);
            assertThat(event.getChatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(event.getLastReadAt()).isEqualTo(lastReadAt);
        }

        @Test
        void fail_lastReadAtIsNull_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.READ_UP_TO)
                    .lastReadAt(null)
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }
    }

    @Nested
    class HandleSetNotice {

        @Test
        void success_validSetNoticePayload_publishesEvent() {
            String noticeMessageId = "msg-123";
            String noticeContent = "This is a notice";
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.SET_NOTICE)
                    .noticeMessageId(noticeMessageId)
                    .noticeMessageContent(noticeContent)
                    .build();

            chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload);

            ArgumentCaptor<ChatWebSocketPayload> captor = ArgumentCaptor.forClass(ChatWebSocketPayload.class);
            verify(chatMessageEventPublisher).publishWsEvent(captor.capture());

            ChatWebSocketPayload event = captor.getValue();
            assertThat(event.getMessageType()).isEqualTo(MessageType.SET_NOTICE);
            assertThat(event.getChatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(event.getNoticeMessageId()).isEqualTo(noticeMessageId);
            assertThat(event.getNoticeMessageContent()).isEqualTo(noticeContent);
        }

        @Test
        void fail_noticeMessageIdIsNull_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.SET_NOTICE)
                    .noticeMessageId(null)
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }
    }

    @Nested
    class HandleDelete {

        @Test
        void success_validDeletePayload_publishesEvent() {
            String deleteId = "msg-456";
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.DELETE)
                    .deleteId(deleteId)
                    .build();

            chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload);

            ArgumentCaptor<ChatWebSocketPayload> captor = ArgumentCaptor.forClass(ChatWebSocketPayload.class);
            verify(chatMessageEventPublisher).publishWsEvent(captor.capture());

            ChatWebSocketPayload event = captor.getValue();
            assertThat(event.getMessageType()).isEqualTo(MessageType.DELETE);
            assertThat(event.getChatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(event.getDeleteId()).isEqualTo(deleteId);
        }

        @Test
        void fail_deleteIdIsNull_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.DELETE)
                    .deleteId(null)
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }
    }

    @Nested
    class UnsupportedMessageType {

        @Test
        void fail_heartbeatType_throwsException() {
            ChatWebSocketPayload payload = ChatWebSocketPayload.builder()
                    .messageType(MessageType.HEARTBEAT)
                    .build();

            assertThatThrownBy(() ->
                    chatWebSocketService.handleIncomingMessage(CHAT_ROOM_ID, MEMBER_ID, payload))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatMessageEventPublisher);
        }
    }
}
