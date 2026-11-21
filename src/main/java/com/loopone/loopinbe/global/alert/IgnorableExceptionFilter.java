package com.loopone.loopinbe.global.alert;

import java.util.List;
import java.util.Set;

/**
 * 알림(Sentry, Slack, Discord)에서 무시해야 할 예외를 판별하는 유틸리티.
 * 클라이언트 연결 끊김 등 서버 장애가 아닌 정상적인 네트워크 이벤트를 필터링한다.
 */
public final class IgnorableExceptionFilter {

    // 클래스명으로 판별 (Tomcat 등 벤더 종속 클래스를 문자열로 비교하여 import 회피)
    private static final Set<String> IGNORABLE_CLASS_NAMES = Set.of(
            "org.apache.catalina.connector.ClientAbortException"
    );

    // 예외 메시지에 포함된 문자열로 판별
    private static final List<String> IGNORABLE_MESSAGES = List.of(
            "Connection reset by peer",
            "Broken pipe"
    );

    private IgnorableExceptionFilter() {
    }

    public static boolean isIgnorable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (IGNORABLE_CLASS_NAMES.contains(current.getClass().getName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                for (String keyword : IGNORABLE_MESSAGES) {
                    if (message.contains(keyword)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
