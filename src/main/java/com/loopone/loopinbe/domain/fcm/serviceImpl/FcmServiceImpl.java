package com.loopone.loopinbe.domain.fcm.serviceImpl;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Message;
import com.loopone.loopinbe.domain.fcm.dto.res.FcmMessageResponse;
import com.loopone.loopinbe.domain.fcm.service.FcmService;
import com.loopone.loopinbe.domain.fcm.service.FcmTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmServiceImpl implements FcmService {

    private final FcmTokenService fcmTokenService;

    @Override
    public void sendMessageTo(FcmMessageResponse message) {
        try {
            String collapseKey = message.getCollapseKey();

            // data 페이로드 (title은 없을 수 있어 null-safe하게 생략)
            Message.Builder builder = Message.builder()
                    .setToken(message.getTargetToken())
                    .putData("body", message.getBody())
                    .putData("alertBody", message.getAlertBody());
            if (message.getTitle() != null) {
                builder.putData("title", message.getTitle());
            }

            // Android: collapseKey가 있으면 채팅방당 갱신(collapse)
            AndroidConfig.Builder androidBuilder = AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH);
            if (collapseKey != null) {
                androidBuilder.setCollapseKey(collapseKey);
            }

            // APNs: title은 null-safe, collapseKey가 있으면 apns-collapse-id로 이전 알림 대체
            ApsAlert.Builder apsAlertBuilder = ApsAlert.builder()
                    .setBody(message.getAlertBody());
            if (message.getTitle() != null) {
                apsAlertBuilder.setTitle(message.getTitle());
            }
            ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                    .putHeader("apns-push-type", "alert")
                    .putHeader("apns-priority", "10")
                    .setAps(Aps.builder()
                            .setAlert(apsAlertBuilder.build())
                            .setMutableContent(true)
                            .setSound("default")
                            .build());
            if (collapseKey != null) {
                apnsBuilder.putHeader("apns-collapse-id", collapseKey);
            }

            Message firebaseMessage = builder
                    .setAndroidConfig(androidBuilder.build())
                    .setApnsConfig(apnsBuilder.build())
                    .build();
            String response = FirebaseMessaging.getInstance().send(firebaseMessage);
            log.info("FCM Admin SDK response: {}", response);
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode errorCode = e.getMessagingErrorCode();

            if (errorCode == MessagingErrorCode.UNREGISTERED
                    || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                // 토큰이 더 이상 유효하지 않음 → 삭제 후 재시도 불필요
                log.warn("FCM token invalid ({}). Removing token for receiverId={}",
                        errorCode, message.getReceiverId());
                if (message.getReceiverId() != null) {
                    fcmTokenService.deleteFcmToken(message.getReceiverId());
                }
                return;
            }

            // UNAVAILABLE, INTERNAL 등 일시적 오류 → throw하여 Kafka 재시도
            log.error("FCM push failed ({})", errorCode, e);
            throw new RuntimeException("FCM send failed, retriable", e);
        }
    }
}
