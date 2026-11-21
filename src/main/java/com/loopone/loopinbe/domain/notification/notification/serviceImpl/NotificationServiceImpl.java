package com.loopone.loopinbe.domain.notification.notification.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmTokenService;
import com.loopone.loopinbe.domain.notification.notification.mapper.NotificationMapper;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.dto.req.NotificationRequest;
import com.loopone.loopinbe.domain.notification.notification.dto.res.NotificationResponse;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.notification.notification.entity.NotificationPage;
import com.loopone.loopinbe.domain.notification.notification.repository.NotificationRepository;
import com.loopone.loopinbe.domain.notification.notification.service.NotificationService;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.domain.notification.setting.service.NotificationSettingService;
import com.loopone.loopinbe.global.common.cursor.CursorUtils;
import com.loopone.loopinbe.global.common.response.PageResponse;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.loopone.loopinbe.global.kafka.event.fcm.FcmEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;
    private final FcmTokenService fcmTokenService;
    private final FcmEventPublisher fcmEventPublisher;
    private final NotificationSettingService notificationSettingService;
    private final MongoTemplate mongoTemplate;

    // Kafka 수신 메시지 저장 & FCM 전송 (초대/참여요청/팔로우)
    @Override
    public void createAndNotifyFromMessage(String message) {
        Notification notification;
        NotificationPayload payload;
        try {
            payload = objectMapper.readValue(message, NotificationPayload.class);
            notification = notificationMapper.toNotification(payload);
        } catch (Exception e) {
            log.error("Failed to deserialize notification message: {}", message, e);
            throw new IllegalArgumentException("Invalid notification payload", e);
        }
        // TTL 만료 시점 설정 (90일)
        notification.setExpireAt(Instant.now().plus(90, ChronoUnit.DAYS));

        // 저장 (인앱: title=헤더, content=본문)
        notificationRepository.save(notification);

        // 팀 초대 알림인데 수신자가 TEAM_INVITE 푸시를 껐으면 인앱 알림 기록만 남기고 FCM은 skip
        if (notification.getTargetObject() == Notification.TargetObject.Invite
                && !notificationSettingService.isEnabled(notification.getReceiverId(), NotificationSettingType.TEAM_INVITE)) {
            log.info("Team invite push suppressed by receiver setting. receiverId={}", notification.getReceiverId());
            return;
        }

        // FCM 전송 (MongoDB는 트랜잭션 불필요 → 저장 직후 발송). 푸시 title=payload.title(), body=NotificationResponse, alertBody=pushContent(없으면 content)
        try {
            String fcmToken = fcmTokenService.getFcmToken(notification.getReceiverId());
            if (fcmToken == null) {
                log.warn("FCM token not found for receiverId: {}", notification.getReceiverId());
                return;
            }
            NotificationResponse notificationResponse = notificationMapper.toNotificationResponse(notification);
            String bodyJson = objectMapper.writeValueAsString(notificationResponse);
            String eventId = "notif:" + notification.getId();
            String alertBody = (payload.pushContent() != null) ? payload.pushContent() : payload.content();
            FcmMessageResponse fcmMessageResponse = FcmMessageResponse.builder()
                    .eventId(eventId)
                    .receiverId(notification.getReceiverId())
                    .targetToken(fcmToken)
                    .title(payload.title())
                    .body(bodyJson)
                    .alertBody(alertBody)
                    .build();
            fcmEventPublisher.publishFcm(fcmMessageResponse);
        } catch (Exception ex) {
            log.error("Failed to send FCM. notificationId={}", notification.getId(), ex);
        }
    }

    // 채팅 알림 저장: 수신자×채팅방당 1개(objectId=chatRoomId)로 upsert하여 최신 메시지로 갱신하고 피드 최상단으로 버블링한다.
    @Override
    public void createChatNotification(NotificationPayload payload) {
        Instant now = Instant.now();
        Query query = new Query(Criteria.where("receiverId").is(payload.receiverId())
                .and("targetObject").is(Notification.TargetObject.Chat)
                .and("objectId").is(payload.objectId()));
        Update update = new Update()
                .set("senderId", payload.senderId())
                .set("senderNickname", payload.senderNickname())
                .set("senderProfileUrl", payload.senderProfileUrl())
                .set("title", payload.title())
                .set("content", payload.content())
                .set("isRead", false)
                .set("createdAt", now)   // 최신 메시지 시각 → createdAt desc 피드에서 최상단으로 버블링
                .set("modifiedAt", now)
                .set("expireAt", now.plus(90, ChronoUnit.DAYS));
        mongoTemplate.upsert(query, update, Notification.class);
    }

    // 알림 목록 조회
    @Override
    public PageResponse<NotificationResponse> getNotifications(String cursor, int size, MemberPrincipal currentUser) {
        checkPageSize(size);

        List<Notification> notifications;
        if (cursor == null) {
            notifications = notificationRepository.findByReceiverIdFirstPage(
                    currentUser.getId(), PageRequest.of(0, size + 1));
        } else {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            notifications = notificationRepository.findByReceiverIdWithCursor(
                    currentUser.getId(), cursorData.createdAt(), cursorData.id(), PageRequest.of(0, size + 1));
        }

        boolean hasNext = notifications.size() > size;
        List<Notification> content = hasNext ? notifications.subList(0, size) : notifications;

        String nextCursor = hasNext
                ? CursorUtils.encode(content.getLast().getCreatedAt(), content.getLast().getId())
                : null;

        List<NotificationResponse> responses = content.stream()
                .map(notificationMapper::toNotificationResponse)
                .toList();

        return PageResponse.of(responses, size, hasNext, nextCursor);
    }

    // 알림 읽음 처리
    @Override
    public void markAsRead(NotificationRequest notificationRequest, MemberPrincipal currentUser) {
        List<String> ids = notificationRequest.notificationIdList();
        List<Notification> notifications = notificationRepository.findAllByIdInAndReceiverId(ids, currentUser.getId());
        if (notifications.size() != ids.size()) {
            throw new ServiceException(ReturnCode.NOTIFICATION_NOT_FOUND);
        }
        notifications.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(notifications);
    }

    // ----------------- 헬퍼 메서드 -----------------

    private void checkPageSize(int size) {
        if (size > NotificationPage.getMaxPageSize()) {
            throw new ServiceException(ReturnCode.PAGE_REQUEST_FAIL);
        }
    }
}
