package com.loopone.loopinbe.domain.notification.notification.mapper;

import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.dto.res.NotificationResponse;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface NotificationMapper {
    // ---------- Notification -> NotificationResponse ----------
    NotificationResponse toNotificationResponse(Notification notification);

    // ---------- NotificationPayload -> Notification ----------
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "modifiedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "expireAt", ignore = true)
    @Mapping(target = "isRead", ignore = true)
    @Mapping(target = "title", source = "payload.title")

    @Mapping(target = "senderId", source = "payload.senderId")
    @Mapping(target = "senderNickname", source = "payload.senderNickname")
    @Mapping(target = "senderProfileUrl", source = "payload.senderProfileUrl")
    @Mapping(target = "receiverId", source = "payload.receiverId")
    @Mapping(target = "objectId", source = "payload.objectId")
    @Mapping(target = "content", source = "payload.content")
    @Mapping(target = "targetObject", source = "payload.targetObject")
    Notification toNotification(NotificationPayload payload);
}
