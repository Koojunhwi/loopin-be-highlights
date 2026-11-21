package com.loopone.loopinbe.global.oauth.handler;

import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.service.AuthService;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.global.oauth.enums.FrontendEnv;
import com.loopone.loopinbe.global.config.properties.FrontendRedirectProperties;
import com.loopone.loopinbe.global.oauth.user.CustomOAuth2User;
import com.loopone.loopinbe.global.oauth.authorization.HttpCookieOAuth2AuthorizationRequestRepository;
import com.loopone.loopinbe.domain.account.oauth.ticket.dto.OAuthTicketPayload;
import com.loopone.loopinbe.domain.account.oauth.ticket.service.OAuthTicketService;
import com.loopone.loopinbe.global.web.cookie.WebAuthCookieFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebOAuth2SuccessHandler implements AuthenticationSuccessHandler {
    private final MemberRepository memberRepository;
    private final AuthService authService;
    private final WebAuthCookieFactory webAuthCookieFactory;
    private final OAuthTicketService oAuthTicketService;
    private final FrontendRedirectProperties frontendRedirectProperties;
    private final HttpCookieOAuth2AuthorizationRequestRepository authRequestRepo;

    @Value("${app.oauth.ticket-ttl-minutes}")
    private long ticketTtlMinutes;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthorizationRequest authReq = authRequestRepo.removeAuthorizationRequest(request, response);
        FrontendEnv env = extractEnv(authReq);

        // principal/email/provider/providerId 안전하게 추출
        ResolvedOAuthPrincipal resolved = resolvePrincipal(authentication);
        if (resolved == null) {
            redirect(response, frontendRedirectProperties.urlFor(env),
                    "OAUTH_PRINCIPAL_INVALID", null);
            return;
        }
        String email = resolved.email();
        Member.OAuthProvider provider = resolved.provider();
        String providerId = resolved.providerId();

        // 필수값 검증: providerId가 없으면 회원을 식별할 수 없다
        if (providerId == null || providerId.isBlank()) {
            log.warn("OAuth2 success but providerId is missing. provider={}", provider);
            redirect(response, frontendRedirectProperties.urlFor(env),
                    "OAUTH_PRINCIPAL_INVALID", null);
            return;
        }

        // 기존 회원은 소셜 계정으로 식별한다 — 이메일이 없어도 로그인할 수 있다
        boolean existing = memberRepository.existsBySocialAccount(provider, providerId);
        if (existing) {
            LoginResponse login = authService.loginBySocialAccount(provider, providerId);

            if (env == FrontendEnv.APP) {
                // 네이티브 앱: 쿠키 대신 URL 파라미터로 토큰 전달
                redirectApp(response, frontendRedirectProperties.urlFor(env),
                        "LOGIN_SUCCESS", login.getAccessToken(), login.getRefreshToken(), null);
            } else {
                // 웹: 기존과 동일하게 쿠키로 토큰 전달
                ResponseCookie accessCookie = webAuthCookieFactory.issueAccess(login.getAccessToken());
                ResponseCookie refreshCookie = webAuthCookieFactory.issueRefresh(login.getRefreshToken());

                response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
                response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

                redirect(response, frontendRedirectProperties.urlFor(env),
                        "LOGIN_SUCCESS", null);
            }
            return;
        }

        // 신규 가입에는 이메일이 필요하다
        if (email == null || email.isBlank()) {
            log.warn("OAuth2 signup but email is missing. provider={}, providerId={}", provider, providerId);
            redirect(response, frontendRedirectProperties.urlFor(env),
                    "OAUTH_EMAIL_MISSING", null);
            return;
        }

        // 신규 → ticket 발급 후 redirect
        String ticket = oAuthTicketService.issue(
                new OAuthTicketPayload(email, provider, providerId),
                Duration.ofMinutes(ticketTtlMinutes)
        );
        if (env == FrontendEnv.APP) {
            redirectApp(response, frontendRedirectProperties.urlFor(env),
                    "SIGNUP_REQUIRED", null, null, ticket);
        } else {
            redirect(response, frontendRedirectProperties.urlFor(env),
                    "SIGNUP_REQUIRED", ticket);
        }
    }

    // Authentication에서 (email, provider, providerId)를 최대한 안전하게 뽑아낸다
    // CustomOAuth2User(커스텀), OidcUser(Google OIDC 등), (기본 OAuth2)
    private ResolvedOAuthPrincipal resolvePrincipal(Authentication authentication) {
        Object principal = authentication.getPrincipal();

        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            log.warn("Authentication is not OAuth2AuthenticationToken. type={}",
                    authentication != null ? authentication.getClass().getName() : "null");
            return null;
        }
        String registrationId = token.getAuthorizedClientRegistrationId(); // google/kakao/naver
        Member.OAuthProvider provider;
        try {
            provider = Member.OAuthProvider.valueOf(registrationId.toUpperCase());
        } catch (Exception e) {
            log.warn("Unknown registrationId/provider: {}", registrationId, e);
            return null;
        }
        String email;
        String providerId;
        if (principal instanceof CustomOAuth2User u) {
            email = u.getEmail();
            providerId = u.getProviderId();
        } else if (principal instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidc) {
            email = oidc.getAttribute("email");
            providerId = oidc.getSubject(); // 보통 sub
        } else if (principal instanceof org.springframework.security.oauth2.core.user.OAuth2User oauth2) {
            email = oauth2.getAttribute("email");
            providerId = oauth2.getName();
        } else {
            log.warn("Unsupported principal type: {}",
                    principal != null ? principal.getClass().getName() : "null");
            return null;
        }
        // providerId가 비어있으면 최소한 principal.getName()으로 fallback
        if ((providerId == null || providerId.isBlank()) && principal instanceof java.security.Principal p) {
            providerId = p.getName();
        }
        return new ResolvedOAuthPrincipal(email, provider, providerId);
    }

    private FrontendEnv extractEnv(OAuth2AuthorizationRequest authReq) {
        if (authReq == null) return FrontendEnv.PROD;
        Object v = authReq.getAttribute("frontend_env");
        if (v == null) return FrontendEnv.PROD;
        try {
            return FrontendEnv.valueOf(String.valueOf(v));
        } catch (Exception e) {
            return FrontendEnv.PROD;
        }
    }

    private void redirectApp(HttpServletResponse response, String baseUrl, String status,
                             String accessToken, String refreshToken, String ticket) throws IOException {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("status", status);
        if (accessToken != null) b.queryParam("accessToken", accessToken);
        if (refreshToken != null) b.queryParam("refreshToken", refreshToken);
        if (ticket != null) b.queryParam("ticket", ticket);

        String url = b.build().toUriString();
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", url);
        response.flushBuffer();
    }

    private void redirect(HttpServletResponse response, String baseUrl, String status, String ticket) throws IOException {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("status", status);
        if (ticket != null) b.queryParam("ticket", ticket);

        String url = b.build().toUriString();
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", url);
        response.flushBuffer();
    }

    private record ResolvedOAuthPrincipal(String email, Member.OAuthProvider provider, String providerId) {}
}

