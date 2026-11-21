package com.loopone.loopinbe.global.webSocket.service;

import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;

public interface ChatWebSocketService {
    /** 수신된 WebSocket 메시지를 검증하고 Kafka 이벤트를 발행한다. */
    void handleIncomingMessage(Long chatRoomId, Long memberId, ChatWebSocketPayload payload);
}
