package com.loopone.loopinbe.domain.account.auth.controller.web;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.auth.dto.req.NativeSocialLoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.req.OAuthLoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.dto.res.NativeSocialLoginResponse;
import com.loopone.loopinbe.domain.account.auth.service.AuthService;
import com.loopone.loopinbe.global.common.response.ApiResponse;
import com.loopone.loopinbe.global.web.cookie.WebAuthCookieFactory;
import com.loopone.loopinbe.global.security.TokenResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rest-api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "WebAuth", description = """
웹 일반 인증 API

소셜 로그인 플로우:
1. 프론트에서 https://api.loopin.co.kr/oauth2/authorization/{provider}로 요청
   - provider: google, kakao, naver, apple
2. 로그인 성공 시 백엔드에서 쿠키 발급
""")
public class WebAuthController {
    private final AuthService authService;
    private final WebAuthCookieFactory webAuthCookieFactory;
    private final TokenResolver tokenResolver;

    // 회원가입 후 로그인 처리
    @PostMapping("/signup-login")
    @Operation(summary = "회원가입 후 로그인 처리", description = "신규 사용자 회원가입 후 로그인 처리합니다. 쿠키로 JWT를 발급합니다.")
    public ResponseEntity<ApiResponse<Void>> signUpAndLogin(
            @Valid @RequestBody OAuthLoginRequest oAuthLoginRequest) {
        LoginResponse login = authService.signUpAndLogin(oAuthLoginRequest.nickname(), oAuthLoginRequest.ticket());

        ResponseCookie accessCookie = webAuthCookieFactory.issueAccess(login.getAccessToken());
        ResponseCookie refreshCookie = webAuthCookieFactory.issueRefresh(login.getRefreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success());
    }

    // 소셜 로그인 (SDK 토큰 검증 방식)
    @PostMapping("/social-login")
    @Operation(summary = "소셜 로그인", description = """
    소셜 SDK로 받은 access token을 검증하고 JWT를 쿠키로 발급합니다.
    - 기존 회원: LOGIN_SUCCESS + 쿠키 발급
    - 신규 회원: SIGNUP_REQUIRED + ticket 반환 (이후 /signup-login 호출)
    """)
    public ResponseEntity<ApiResponse<?>> nativeSocialLogin(
            @RequestBody @Valid NativeSocialLoginRequest request) {
        NativeSocialLoginResponse response = authService.nativeSocialLogin(request);

        if ("LOGIN_SUCCESS".equals(response.status())) {
            ResponseCookie accessCookie = webAuthCookieFactory.issueAccess(response.accessToken());
            ResponseCookie refreshCookie = webAuthCookieFactory.issueRefresh(response.refreshToken());
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(ApiResponse.success());
        }

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 로그아웃
    @PostMapping("/logout")
    @Operation(summary = "로그아웃", description = "현재 로그인된 사용자가 로그아웃합니다.")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, @AuthenticationPrincipal MemberPrincipal currentUser) {
        String accessToken = tokenResolver.resolveAccess(request);
        authService.logout(currentUser, accessToken);

        ResponseCookie accessCookie = webAuthCookieFactory.expireAccess();
        ResponseCookie refreshCookie = webAuthCookieFactory.expireRefresh();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success());
    }

    // accessToken 재발급
    @GetMapping("/refresh-token")
    @Operation(summary = "accessToken 재발급", description = "refresh 토큰을 사용하여 access 토큰을 재발급합니다.")
    public ResponseEntity<ApiResponse<Void>> refreshToken(HttpServletRequest request) {
        String refreshToken = tokenResolver.resolveRefresh(request);
        LoginResponse refreshed = authService.refreshToken(refreshToken);

        ResponseCookie accessCookie = webAuthCookieFactory.issueAccess(refreshed.getAccessToken()); // access만 갱신
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .body(ApiResponse.success());
    }
}
