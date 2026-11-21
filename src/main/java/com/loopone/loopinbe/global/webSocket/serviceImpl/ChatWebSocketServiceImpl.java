package com.loopone.loopinbe.global.webSocket.serviceImpl;

import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.TeamChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType;
import com.loopone.loopinbe.global.kafka.event.chatMessage.ChatMessageEventPublisher;
import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;
import com.loopone.loopinbe.global.webSocket.service.ChatWebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatWebSocketServiceImpl implements ChatWebSocketService {

    private final ChatMessageEventPublisher chatMessageEventPublisher;

    @Override
    public void handleIncomingMessage(Long chatRoomId, Long memberId, ChatWebSocketPayload payload) {
        switch (payload.getMessageType()) {
            case MESSAGE -> handleMessage(chatRoomId, memberId, payload);
            case READ_UP_TO -> handleReadUpTo(chatRoomId, memberId, payload);
            case SET_NOTICE -> handleSetNotice(chatRoomId, memberId, payload);
            case DELETE -> handleDelete(chatRoomId, memberId, payload);
            default -> throw new IllegalArgumentException(
                    "Unsupported messageType: " + payload.getMessageType());
        }
    }

    private void handleMessage(Long chatRoomId, Long memberId, ChatWebSocketPayload payload) {
        if (payload.getClientMessageId() == null) {
            throw new IllegalArgumentException("clientMessageId is required");
        }
        String content = (payload.getTeamChatMessageResponse() != null)
                ? payload.getTeamChatMessageResponse().getContent()
                : null;
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }

        ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                .messageType(MessageType.MESSAGE)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .clientMessageId(payload.getClientMessageId())
                .teamChatMessageResponse(TeamChatMessageResponse.builder()
                        .content(content)
                        .build())
                .build();
        chatMessageEventPublisher.publishWsEvent(event);
    }

    private void handleReadUpTo(Long chatRoomId, Long memberId, ChatWebSocketPayload payload) {
        if (payload.getLastReadAt() == null) {
            throw new IllegalArgumentException("lastReadAt is required");
        }

        ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                .messageType(MessageType.READ_UP_TO)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .lastReadAt(payload.getLastReadAt())
                .build();
        chatMessageEventPublisher.publishWsEvent(event);
    }

    private void handleSetNotice(Long chatRoomId, Long memberId, ChatWebSocketPayload payload) {
        if (payload.getNoticeMessageId() == null) {
            throw new IllegalArgumentException("messageId is required");
        }

        ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                .messageType(MessageType.SET_NOTICE)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .noticeMessageId(payload.getNoticeMessageId())
                .noticeMessageContent(payload.getNoticeMessageContent())
                .build();
        chatMessageEventPublisher.publishWsEvent(event);
    }

    private void handleDelete(Long chatRoomId, Long memberId, ChatWebSocketPayload payload) {
        if (payload.getDeleteId() == null) {
            throw new IllegalArgumentException("messageId is required");
        }

        ChatWebSocketPayload event = ChatWebSocketPayload.builder()
                .messageType(MessageType.DELETE)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .deleteId(payload.getDeleteId())
                .build();
        chatMessageEventPublisher.publishWsEvent(event);
    }
}
