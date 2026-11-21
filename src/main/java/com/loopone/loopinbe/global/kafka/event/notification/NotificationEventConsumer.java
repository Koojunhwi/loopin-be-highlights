package com.loopone.loopinbe.global.kafka.event.notification;

import com.loopone.loopinbe.domain.notification.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

import static com.loopone.loopinbe.global.constants.KafkaKey.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {
    private final NotificationService notificationService;

    @KafkaListener(
            topics = { FOLLOW_NOTIFICATION_TOPIC, INVITE_TOPIC, JOIN_REQUEST_TOPIC },
            groupId = NOTIFICATION_GROUP_ID, containerFactory = KAFKA_LISTENER_CONTAINER
    )
    public void consumeNotification(ConsumerRecord<String, String> rec) {
        final String topic = rec.topic();
        if (!Set.of(FOLLOW_NOTIFICATION_TOPIC, INVITE_TOPIC, JOIN_REQUEST_TOPIC).contains(topic)) {
            // 알 수 없는 토픽이면 "실패"로 처리해서 ErrorHandler가 재시도/ DL(T)로 보내게 유도
            throw new IllegalArgumentException("Unknown notification topic: " + topic);
        }
        // 메시지 저장 + 커밋 후 FCM 발송은 서비스에서 처리
        notificationService.createAndNotifyFromMessage(rec.value());
    }
}
