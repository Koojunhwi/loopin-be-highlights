package com.loopone.loopinbe.global.kafka.event.chatMessage.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatMessagePayload;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.TeamChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType;
import com.loopone.loopinbe.domain.chat.chatMessage.mapper.ChatMessageMapper;
import com.loopone.loopinbe.domain.chat.chatMessage.service.ChatMessageService;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomMemberService;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomService;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmTokenService;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.notification.notification.service.NotificationService;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.domain.notification.setting.service.NotificationSettingService;
import com.loopone.loopinbe.global.kafka.event.chatMessage.service.ChatMessageEventService;
import com.loopone.loopinbe.global.kafka.event.fcm.FcmEventPublisher;
import com.loopone.loopinbe.global.webSocket.handler.ChatWebSocketHandler;
import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageEventServiceImpl implements ChatMessageEventService {

    private final ObjectMapper objectMapper;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChatRoomService chatRoomService;
    private final ChatRoomMemberService chatRoomMemberService;
    private final ChatMessageService chatMessageService;
    private final ChatMessageMapper chatMessageMapper;
    private final FcmTokenService fcmTokenService;
    private final FcmEventPublisher fcmEventPublisher;
    private final NotificationSettingService notificationSettingService;
    private final NotificationService notificationService;

    // 채팅 푸시 미리보기 최대 글자 수
    private static final int PREVIEW_MAX = 20;

    @Override
    public void processMessageEvent(ChatWebSocketPayload event) {
        Long chatRoomId = event.getChatRoomId();
        Long memberId = event.getMemberId();
        UUID clientMessageId = event.getClientMessageId();
        String content = (event.getTeamChatMessageResponse() != null)
                ? event.getTeamChatMessageResponse().getContent()
                : null;

        if (memberId == null || clientMessageId == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    String.format("Invalid MESSAGE event. roomId=%d, memberId=%s, clientMessageId=%s, hasContent=%s",
                            chatRoomId, memberId, clientMessageId, content != null));
        }

        // inbound payload 생성 (idempotent key)
        Instant now = Instant.now();
        ChatMessagePayload inbound = new ChatMessagePayload(
                "u:" + clientMessageId,
                clientMessageId,
                chatRoomId,
                memberId,
                content,
                null, null, null, null,
                ChatMessage.AuthorType.USER,
                false, false,
                now, now
        );

        // Mongo upsert (멱등 저장)
        ChatMessagePayload saved = chatMessageService.processInbound(inbound);

        // WS 응답 DTO로 매핑 (저장된 결과 기준)
        Map<Long, Member> memberMap = chatMessageMapper.loadMembersFromPayload(List.of(saved));
        TeamChatMessageResponse savedResp = chatMessageMapper.toTeamChatMessageResponse(saved, memberMap, memberId);

        ChatWebSocketPayload out = ChatWebSocketPayload.builder()
                .messageType(MessageType.MESSAGE)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .clientMessageId(saved.clientMessageId())
                .teamChatMessageResponse(savedResp)
                .build();

        broadcastToRoom(chatRoomId, out);

        // 발신자 제외 방 멤버에게 FCM 푸시 fan-out (실패해도 브로드캐스트/컨슈머에는 영향 없음)
        sendChatPushNotifications(chatRoomId, memberId, saved, memberMap);
    }

    // 방 멤버(발신자 제외)에게 인앱 알림을 저장(채팅방당 1개 upsert 갱신)한 뒤 FCM 푸시를 발송한다.
    // TEAM_CHAT을 끈 수신자는 인앱 알림만 갱신하고 FCM은 skip한다. 포그라운드 억제는 클라이언트가 body의 chatRoomId로 판단한다.
    private void sendChatPushNotifications(Long chatRoomId, Long senderId, ChatMessagePayload saved, Map<Long, Member> memberMap) {
        try {
            List<Long> receiverIds = chatRoomMemberService.getOtherMemberIds(chatRoomId, senderId);
            if (receiverIds == null || receiverIds.isEmpty()) return;

            Member sender = memberMap.get(senderId);
            String senderNickname = (sender != null && sender.getNickname() != null) ? sender.getNickname() : "알 수 없음";
            String senderProfileUrl = (sender != null) ? sender.getProfileImageUrl() : null;
            String preview = buildPreview(saved.content());
            String inAppTitle = senderNickname + "님이 메시지를 보냈어요";

            // 1) 인앱 알림 저장 (채팅방당 1개 upsert, 모든 수신자 — TEAM_CHAT 설정과 무관)
            for (Long receiverId : receiverIds) {
                try {
                    NotificationPayload payload = new NotificationPayload(
                            senderId,
                            senderNickname,
                            senderProfileUrl,
                            receiverId,
                            chatRoomId,                    // objectId: 알림 클릭 시 이동할 채팅방
                            inAppTitle,                    // title(인앱 헤더)
                            preview,                       // content(미리보기 20자)
                            null,                          // pushContent(미사용)
                            Notification.TargetObject.Chat
                    );
                    notificationService.createChatNotification(payload);
                } catch (Exception e) {
                    log.warn("Failed to save chat notification. chatRoomId={}, receiverId={}", chatRoomId, receiverId, e);
                }
            }

            // 2) FCM 발송 (TEAM_CHAT을 켠 수신자에게만, 채팅방당 collapse). title=닉네임, alertBody=미리보기
            List<Long> fcmReceiverIds = notificationSettingService.filterEnabled(receiverIds, NotificationSettingType.TEAM_CHAT);
            if (fcmReceiverIds.isEmpty()) return;

            String bodyJson = objectMapper.writeValueAsString(Map.of(
                    "chatRoomId", chatRoomId,
                    "messageId", saved.id(),
                    "senderId", senderId
            ));
            String collapseKey = "chat-" + chatRoomId;

            for (Long receiverId : fcmReceiverIds) {
                try {
                    String token = fcmTokenService.getFcmToken(receiverId);
                    if (token == null) continue;
                    FcmMessageResponse fcm = FcmMessageResponse.builder()
                            .eventId("chat:" + saved.id() + ":" + receiverId)
                            .receiverId(receiverId)
                            .targetToken(token)
                            .title(senderNickname)
                            .body(bodyJson)
                            .alertBody(preview)
                            .collapseKey(collapseKey)
                            .build();
                    fcmEventPublisher.publishFcm(fcm);
                } catch (Exception e) {
                    log.warn("Failed to publish chat FCM. chatRoomId={}, receiverId={}", chatRoomId, receiverId, e);
                }
            }
        } catch (Exception e) {
            log.error("Chat notification/FCM fan-out failed. chatRoomId={}", chatRoomId, e);
        }
    }

    // 메시지 내용 미리보기: 앞 PREVIEW_MAX 글자, 초과 시 말줄임표(…) 추가
    private String buildPreview(String content) {
        if (content == null) return "";
        String trimmed = content.strip();
        if (trimmed.length() <= PREVIEW_MAX) return trimmed;
        return trimmed.substring(0, PREVIEW_MAX) + "…";
    }

    @Override
    public void processAttachmentEvent(ChatWebSocketPayload event) {
        broadcastToRoom(event.getChatRoomId(), event);
    }

    @Override
    public void processReadUpToEvent(ChatWebSocketPayload event) {
        Long chatRoomId = event.getChatRoomId();
        Long memberId = event.getMemberId();
        Instant lastReadAt = event.getLastReadAt();

        if (memberId == null || lastReadAt == null) {
            throw new IllegalArgumentException(
                    String.format("Invalid READ_UP_TO event. roomId=%d, memberId=%s, lastReadAt=%s",
                            chatRoomId, memberId, lastReadAt));
        }

        Instant updated = chatRoomMemberService.updateLastReadAt(chatRoomId, memberId, lastReadAt);

        ChatWebSocketPayload out = ChatWebSocketPayload.builder()
                .messageType(MessageType.READ_UP_TO)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .lastReadAt(updated)
                .build();

        broadcastToRoom(chatRoomId, out);
    }

    @Override
    public void processSetNoticeEvent(ChatWebSocketPayload event) {
        Long chatRoomId = event.getChatRoomId();
        Long memberId = event.getMemberId();
        String noticeMessageId = event.getNoticeMessageId();
        String noticeMessageContent = event.getNoticeMessageContent();

        if (memberId == null || noticeMessageId == null || noticeMessageId.isBlank()) {
            throw new IllegalArgumentException(
                    String.format("Invalid SET_NOTICE event. roomId=%d, memberId=%s, noticeMessageId=%s",
                            chatRoomId, memberId, noticeMessageId));
        }

        boolean updated = chatRoomService.updateNoticeMessageContent(chatRoomId, noticeMessageContent);
        if (!updated) {
            log.warn("SET_NOTICE chatroom not found. roomId={}", chatRoomId);
            return;
        }

        ChatWebSocketPayload out = ChatWebSocketPayload.builder()
                .messageType(MessageType.SET_NOTICE)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .noticeMessageId(noticeMessageId)
                .noticeMessageContent(noticeMessageContent)
                .build();

        broadcastToRoom(chatRoomId, out);
    }

    @Override
    public void processDeleteEvent(ChatWebSocketPayload event) {
        Long chatRoomId = event.getChatRoomId();
        Long memberId = event.getMemberId();
        String messageId = event.getDeleteId();

        if (memberId == null || messageId == null) {
            throw new IllegalArgumentException(
                    String.format("Invalid DELETE event. roomId=%d, memberId=%s, messageId=%s",
                            chatRoomId, memberId, messageId));
        }

        chatMessageService.deleteChatMessage(messageId, memberId);

        ChatWebSocketPayload out = ChatWebSocketPayload.builder()
                .messageType(MessageType.DELETE)
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .deleteId(messageId)
                .build();

        broadcastToRoom(chatRoomId, out);
    }

    private void broadcastToRoom(Long chatRoomId, ChatWebSocketPayload payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            chatWebSocketHandler.broadcastToRoom(chatRoomId, payloadJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize WS payload", e);
        }
    }
}
