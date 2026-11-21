package com.loopone.loopinbe.global.kafka.event.chatMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.global.kafka.event.chatMessage.service.ChatMessageEventService;
import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.loopone.loopinbe.global.constants.KafkaKey.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageEventConsumer {
    private final ObjectMapper objectMapper;
    private final ChatMessageEventService chatMessageEventService;

    @KafkaListener(
            topics = {CHAT_MESSAGE_TOPIC, CHAT_READ_UP_TO_TOPIC, CHAT_SET_NOTICE_TOPIC, CHAT_DELETE_TOPIC},
            groupId = CHAT_GROUP_ID,
            containerFactory = KAFKA_LISTENER_CONTAINER
    )
    public void consumeWsEvent(ConsumerRecord<String, String> rec) {
        try {
            ChatWebSocketPayload event = objectMapper.readValue(rec.value(), ChatWebSocketPayload.class);
            Long chatRoomId = event.getChatRoomId();
            if (chatRoomId == null) {
                log.warn("WS event missing chatRoomId. topic={}, key={}", rec.topic(), rec.key());
                return;
            }
            switch (event.getMessageType()) {
                case MESSAGE -> chatMessageEventService.processMessageEvent(event);
                case ATTACHMENT -> chatMessageEventService.processAttachmentEvent(event);
                case READ_UP_TO -> chatMessageEventService.processReadUpToEvent(event);
                case SET_NOTICE -> chatMessageEventService.processSetNoticeEvent(event);
                case DELETE -> chatMessageEventService.processDeleteEvent(event);
                default -> log.warn("Unhandled event type: {} topic={}, key={}",
                        event.getMessageType(), rec.topic(), rec.key());
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid WS event data. topic={}, key={}, message={}", rec.topic(), rec.key(), e.getMessage());
        } catch (Exception e) {
            // 여기서 throw 하면 Kafka가 재시도/리밸런싱 걸 수 있음.
            // "일시적 장애"면 throw가 맞고, "데이터 오류"면 return이 맞음.
            log.error("Failed to handle WS event. topic={}, key={}", rec.topic(), rec.key(), e);
            throw new RuntimeException(e);
        }
    }
}
