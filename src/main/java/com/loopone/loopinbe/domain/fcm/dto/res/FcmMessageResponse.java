package com.loopone.loopinbe.domain.fcm.dto.res;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FcmMessageResponse {
    private String eventId;
    private Long receiverId;
    private String targetToken;
    private String title;
    private String body;
    private String alertBody;
    private String collapseKey;
}
