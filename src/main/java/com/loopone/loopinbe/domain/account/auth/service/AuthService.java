package com.loopone.loopinbe.domain.account.auth.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.auth.dto.req.LoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.req.NativeSocialLoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.dto.res.NativeSocialLoginResponse;
import com.loopone.loopinbe.domain.account.member.entity.Member;

public interface AuthService {
    // 회원가입 후 로그인 처리
    LoginResponse signUpAndLogin(String nickname, String ticket);

    // 이메일 로그인 (개발/스테이징 전용)
    LoginResponse login(LoginRequest loginRequest);

    // 소셜 계정(provider + providerId) 로그인
    LoginResponse loginBySocialAccount(Member.OAuthProvider provider, String providerId);

    // 네이티브 앱 소셜 로그인 (SDK 토큰 검증)
    NativeSocialLoginResponse nativeSocialLogin(NativeSocialLoginRequest request);

    // 로그아웃
    void logout(MemberPrincipal currentUser, String accessToken);

    // 회원 탈퇴 시 인증 정리 (logout + FCM 토큰 삭제)
    void cleanUpOnWithdraw(MemberPrincipal currentUser, String accessToken);

    // accessToken 재발급
    LoginResponse refreshToken(String refreshToken);
}
