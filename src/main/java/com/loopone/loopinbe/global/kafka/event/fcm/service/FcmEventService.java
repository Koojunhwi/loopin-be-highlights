package com.loopone.loopinbe.global.kafka.event.fcm.service;

import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;

public interface FcmEventService {
    void processFcmEvent(FcmMessageResponse message);
}
