package com.loopone.loopinbe.global.kafka.event.fcm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.global.kafka.event.fcm.service.FcmEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.loopone.loopinbe.global.constants.KafkaKey.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcmEventConsumer {
    private final ObjectMapper objectMapper;
    private final FcmEventService fcmEventService;

    @KafkaListener(topics = FCM_SEND_TOPIC, groupId = FCM_GROUP_ID, containerFactory = KAFKA_LISTENER_CONTAINER)
    public void consumeFcm(ConsumerRecord<String, String> rec) {
        String json = rec.value();
        final FcmMessageResponse msg;
        try {
            msg = objectMapper.readValue(json, FcmMessageResponse.class);
        } catch (Exception e) {
            log.error("Failed to deserialize FCM message: {}", json, e);
            throw new IllegalArgumentException("Invalid FCM message payload", e);
        }
        fcmEventService.processFcmEvent(msg);
    }
}
