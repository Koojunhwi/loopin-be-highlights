package com.loopone.loopinbe.global.alert;

import com.loopone.loopinbe.global.config.properties.SentryReportProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RealTimeAlertSender {

    private final WebClient webClient;
    private final SentryReportProperties properties;
    private final ConcurrentHashMap<String, Instant> recentAlerts = new ConcurrentHashMap<>();

    public RealTimeAlertSender(SentryReportProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.create();
    }

    @Async
    public void send(Exception ex) {
        if (IgnorableExceptionFilter.isIgnorable(ex)) {
            log.debug("무시 대상 예외, 알림 생략: {}", ex.getClass().getSimpleName());
            return;
        }

        if (!properties.isSlackEnabled() && !properties.isDiscordEnabled()) {
            return;
        }

        String deduplicationKey = buildDeduplicationKey(ex);
        Instant now = Instant.now();
        long cooldownSeconds = properties.alertCooldownMinutes() * 60L;

        Instant lastSent = recentAlerts.get(deduplicationKey);
        if (lastSent != null && now.isBefore(lastSent.plusSeconds(cooldownSeconds))) {
            log.debug("실시간 알림 쿨다운 중 (key={})", deduplicationKey);
            return;
        }
        recentAlerts.put(deduplicationKey, now);

        String message = buildMessage(ex);

        if (properties.isSlackEnabled()) {
            sendSlack(message);
        }
        if (properties.isDiscordEnabled()) {
            sendDiscord(message);
        }
    }

    @Scheduled(fixedRate = 600_000) // 10분마다 만료된 엔트리 정리
    public void cleanupExpiredEntries() {
        Instant cutoff = Instant.now().minusSeconds(properties.alertCooldownMinutes() * 60L);
        recentAlerts.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private String buildDeduplicationKey(Exception ex) {
        String className = ex.getClass().getName();
        String message = ex.getMessage() != null ? ex.getMessage() : "";
        // 메시지가 길면 앞 200자만 사용
        if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        return className + ":" + message;
    }

    private String buildMessage(Exception ex) {
        String className = ex.getClass().getSimpleName();
        String message = ex.getMessage() != null ? ex.getMessage() : "(no message)";
        String location = extractLocation(ex);

        StringBuilder sb = new StringBuilder();
        sb.append("[ALERT] 실시간 에러 발생\n\n");
        sb.append("예외: `").append(className).append("`\n");
        sb.append("메시지: ").append(truncate(message, 500)).append("\n");
        if (!location.isEmpty()) {
            sb.append("위치: ").append(location).append("\n");
        }
        sb.append("시각: ").append(Instant.now()).append("\n");
        sb.append("환경: production");
        return sb.toString();
    }

    private String extractLocation(Exception ex) {
        return Arrays.stream(ex.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.loopone.loopinbe"))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                        + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                .orElse("");
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private void sendSlack(String message) {
        try {
            webClient.post()
                    .uri(properties.slackWebhookUrl())
                    .bodyValue(Map.of("text", message))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("실시간 에러 알림 Slack 전송 완료");
        } catch (Exception e) {
            log.error("실시간 에러 알림 Slack 전송 실패", e);
        }
    }

    private void sendDiscord(String message) {
        try {
            webClient.post()
                    .uri(properties.discordWebhookUrl())
                    .bodyValue(Map.of("content", message))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("실시간 에러 알림 Discord 전송 완료");
        } catch (Exception e) {
            log.error("실시간 에러 알림 Discord 전송 실패", e);
        }
    }
}
