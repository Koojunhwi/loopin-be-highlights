package com.loopone.loopinbe.domain.notification.notification.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.dto.req.NotificationRequest;
import com.loopone.loopinbe.domain.notification.notification.dto.res.NotificationResponse;
import com.loopone.loopinbe.global.common.response.PageResponse;

public interface NotificationService {
    // Kafka 수신 메시지 저장 & FCM 전송
    void createAndNotifyFromMessage(String message);

    // 채팅 알림 저장
    void createChatNotification(NotificationPayload payload);

    // 알림 목록 조회
    PageResponse<NotificationResponse> getNotifications(String cursor, int size, MemberPrincipal currentUser);

    // 알림 읽음 처리
    void markAsRead(NotificationRequest notificationRequest, MemberPrincipal currentUser);
}
