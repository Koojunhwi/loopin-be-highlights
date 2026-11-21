package com.loopone.loopinbe.global.oauth.enums;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;

public enum FrontendEnv {
    LOCAL("local.loopin.co.kr"),
    DEVELOP("develop.loopin.co.kr"),
    PROD("loopin.co.kr"),
    APP(null);

    private final String host;
    FrontendEnv(String host) { this.host = host; }

    public static FrontendEnv fromRequest(HttpServletRequest request) {
        // platform=app 쿼리 파라미터가 있으면 네이티브 앱 요청
        if ("app".equalsIgnoreCase(request.getParameter("platform"))) {
            return APP;
        }

        String originOrRef = Optional.ofNullable(request.getHeader("Origin"))
                .orElse(request.getHeader("Referer"));
        if (originOrRef == null) return PROD;

        try {
            URI uri = URI.create(originOrRef);
            String host = uri.getHost();
            if (host == null) return PROD;
            if (host.equalsIgnoreCase("local.loopin.co.kr"))   return LOCAL;
            if (host.equalsIgnoreCase("develop.loopin.co.kr")) return DEVELOP;
            if (host.equalsIgnoreCase("loopin.co.kr") || host.equalsIgnoreCase("www.loopin.co.kr")) return PROD;
        } catch (IllegalArgumentException ignored) {}
        return PROD;
    }
}
