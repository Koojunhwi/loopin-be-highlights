package com.loopone.loopinbe.global.kafka.event.chatMessage.service;

import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;

public interface ChatMessageEventService {
    void processMessageEvent(ChatWebSocketPayload event);
    void processAttachmentEvent(ChatWebSocketPayload event);
    void processReadUpToEvent(ChatWebSocketPayload event);
    void processSetNoticeEvent(ChatWebSocketPayload event);
    void processDeleteEvent(ChatWebSocketPayload event);
}
