package com.loopone.loopinbe.global.security;

import com.loopone.loopinbe.domain.account.auth.service.AccessTokenDenyListService;
import com.loopone.loopinbe.domain.account.auth.service.MemberDetailsService;
import com.loopone.loopinbe.domain.account.auth.service.RefreshTokenService;
import io.sentry.Sentry;
import io.sentry.protocol.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberDetailsService memberDetailsService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenDenyListService accessTokenDenyListService;
    private final TokenResolver tokenResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 우리 애플리케이션 경로가 아닌 요청은 필터 스킵 (봇/스캐너 요청 무시)
        if (!path.startsWith("/rest-api/")
                && !path.startsWith("/api/")
                && !path.startsWith("/swagger-ui")
                && !path.startsWith("/v3/api-docs")
                && !path.startsWith("/actuator/prometheus")
                && !path.startsWith("/actuator/health")
                && !path.equals("/ws/chat")
                && !path.startsWith("/oauth2")
                && !path.startsWith("/login/oauth2")) {
            return true;
        }

        // 인증이 필요 없는 URL 리스트
        return path.startsWith("/rest-api/v1/auth/signup-login")
                || path.startsWith("/rest-api/v1/auth/login")
                || path.startsWith("/rest-api/v1/auth/social-login")
                || path.startsWith("/rest-api/v1/auth/refresh-token")
                || path.startsWith("/rest-api/v1/member/available")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/rest-api/v1/find-password")
                || path.startsWith("/actuator/prometheus")
                || path.startsWith("/actuator/health")
                || path.equals("/ws/chat")
                || path.startsWith("/oauth2")
                || path.startsWith("/login/oauth2");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if(CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String accessToken = tokenResolver.resolveAccess(request); // 쿠키 우선, 없으면 Bearer

        // JWT가 없을 경우
        if (!StringUtils.hasText(accessToken)) {
            log.warn("JWT 토큰이 없습니다.");
            responseUnauthorized(response, "인증 실패");
            return;
        }
        try {
            // 토큰 유효성 검사
            if (!jwtTokenProvider.validateAccessToken(accessToken)) {
                log.warn("유효하지 않은 Access Token");
                responseUnauthorized(response, "유효하지 않은 Access Token 입니다.");
                return;
            }
            // Deny-list(즉시 무효화) 체크
            String jti = null;
            try {
                jti = jwtTokenProvider.getJti(accessToken); // jti 없는 과거 토큰 대비
            } catch (Exception ignore) {} // 과거 토큰 호환
            if (jti != null && accessTokenDenyListService.isDenied(jti)) {
                log.warn("Deny-list에 등록된 토큰입니다. jti={}", jti);
                responseUnauthorized(response, "이미 로그아웃된 토큰입니다.");
                return;
            }
            // memberId 추출
            Long memberId = jwtTokenProvider.getMemberIdFromToken(accessToken);
            log.info("정상적으로 사용자 정보를 토큰으로부터 가져왔습니다. memberId: {}", memberId);
            UserDetails userDetails = memberDetailsService.loadUserById(memberId);

            // JWT 기반 인증 객체 생성 (비밀번호 없이)
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // SecurityContextHolder에 직접 세팅
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Sentry 유저 컨텍스트 설정
            User sentryUser = new User();
            sentryUser.setId(String.valueOf(memberId));
            sentryUser.setUsername(userDetails.getUsername());
            Sentry.setUser(sentryUser);
        } catch (Exception e) {
            log.error("JWT 인증 처리 실패: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
            try {
                Long memberId = jwtTokenProvider.getMemberIdFromToken(accessToken);
                refreshTokenService.deleteRefreshToken(memberId);
            } catch (Exception ignored) {
            }
        }
        filterChain.doFilter(request, response);
    }

    private void responseUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\": \"" + message + "\"}");
    }
}
