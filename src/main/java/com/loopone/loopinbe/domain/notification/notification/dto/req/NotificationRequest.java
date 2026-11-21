package com.loopone.loopinbe.domain.notification.notification.dto.req;

import java.util.List;

public record NotificationRequest(
        List<String> notificationIdList
) {}
