package com.loopone.loopinbe.domain.notification.notification.dto;

import com.loopone.loopinbe.domain.notification.notification.entity.Notification;

public record NotificationPayload(
        Long senderId,
        String senderNickname,
        String senderProfileUrl,
        Long receiverId,
        Long objectId,
        String title,
        String content,
        String pushContent,
        Notification.TargetObject targetObject
) {
    public NotificationPayload(Long senderId, String senderNickname, String senderProfileUrl,
                               Long receiverId, Long objectId, String content,
                               Notification.TargetObject targetObject) {
        this(senderId, senderNickname, senderProfileUrl, receiverId, objectId,
                null, content, null, targetObject);
    }
}
