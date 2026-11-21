package com.loopone.loopinbe.domain.account.auth.controller.web;

import com.loopone.loopinbe.domain.account.auth.dto.req.LoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.service.AuthService;
import com.loopone.loopinbe.global.common.response.ApiResponse;
import com.loopone.loopinbe.global.web.cookie.WebAuthCookieFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("!prod")
@RestController
@RequestMapping("/rest-api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "WebAuth-Dev", description = "개발/스테이징 전용 인증 API (프로덕션 비활성화)")
public class WebAuthDevController {
    private final AuthService authService;
    private final WebAuthCookieFactory webAuthCookieFactory;

    @PostMapping("/login")
    @Operation(summary = "[DEV] 이메일 로그인", description = "이메일만으로 로그인합니다. 프로덕션에서는 비활성화됩니다.")
    public ResponseEntity<ApiResponse<Void>> login(@RequestBody @Valid LoginRequest loginRequest) {
        LoginResponse login = authService.login(loginRequest);
        ResponseCookie accessCookie = webAuthCookieFactory.issueAccess(login.getAccessToken());
        ResponseCookie refreshCookie = webAuthCookieFactory.issueRefresh(login.getRefreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success());
    }
}
