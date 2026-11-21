package com.loopone.loopinbe.global.kafka.event.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.loopone.loopinbe.domain.loop.aijob.enums.AiJobType;
import com.loopone.loopinbe.domain.loop.aijob.service.AiJobService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.loopone.loopinbe.global.constants.KafkaKey.*;

@Component
@RequiredArgsConstructor
public class AiEventConsumer {
    private final AiJobService aiJobService;

    @KafkaListener(topics = OPEN_AI_CREATE_TOPIC, groupId = OPEN_AI_GROUP_ID, containerFactory = KAFKA_LISTENER_CONTAINER)
    public void consumeAiCreateLoop(ConsumerRecord<String, String> rec) throws JsonProcessingException {
        handleAiEvent(rec, AiJobType.CREATE);
    }

    @KafkaListener(topics = OPEN_AI_UPDATE_TOPIC, groupId = OPEN_AI_GROUP_ID, containerFactory = KAFKA_LISTENER_CONTAINER)
    public void consumeAiUpdateLoop(ConsumerRecord<String, String> rec) throws JsonProcessingException {
        handleAiEvent(rec, AiJobType.UPDATE);
    }

    private void handleAiEvent(ConsumerRecord<String, String> rec, AiJobType jobType) throws JsonProcessingException {
        aiJobService.createJob(rec.value(), jobType);
    }
}
