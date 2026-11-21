package com.loopone.loopinbe.domain.account.auth.serviceImpl;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.auth.dto.req.LoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.req.NativeSocialLoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.dto.res.NativeSocialLoginResponse;
import com.loopone.loopinbe.domain.account.oauth.ticket.dto.OAuthTicketPayload;
import com.loopone.loopinbe.domain.account.oauth.ticket.service.OAuthTicketService;
import com.loopone.loopinbe.global.security.JwtTokenProvider;
import com.loopone.loopinbe.domain.account.auth.service.AccessTokenDenyListService;
import com.loopone.loopinbe.domain.account.auth.service.AuthService;
import com.loopone.loopinbe.domain.account.auth.service.RefreshTokenService;
import com.loopone.loopinbe.domain.account.auth.service.SocialTokenVerifier;
import com.loopone.loopinbe.domain.account.auth.service.SocialTokenVerifier.SocialUserInfo;
import com.loopone.loopinbe.domain.account.member.dto.req.MemberCreateRequest;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.domain.account.member.service.MemberService;
import com.loopone.loopinbe.domain.fcm.service.FcmTokenService;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.loopone.loopinbe.global.webSocket.util.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final MemberService memberService;
    private final AccessTokenDenyListService accessTokenDenyListService;
    private final WsSessionRegistry wsSessionRegistry;
    private final OAuthTicketService oAuthTicketService;
    private final FcmTokenService fcmTokenService;
    private final SocialTokenVerifier socialTokenVerifier;
    private final EncryptionProperties encryptionProperties;

    @Value("${custom.accessToken.expiration}")
    private Duration accessTokenExpiration;

    @Value("${custom.refreshToken.expiration}")
    private Duration refreshTokenExpiration;

    @Value("${app.oauth.ticket-ttl-minutes}")
    private long ticketTtlMinutes;

    // 회원가입 후 로그인 처리
    @Override
    @Transactional
    public LoginResponse signUpAndLogin(String nickname, String ticket) {
        OAuthTicketPayload payload = oAuthTicketService.consume(ticket);
        MemberCreateRequest memberCreateRequest = MemberCreateRequest.builder()
                .email(payload.email())
                .nickname(nickname)
                .provider(payload.provider())
                .providerId(payload.providerId())
                .build();
        Member newMember = memberService.regularSignUp(memberCreateRequest);
        // 회원가입 직후 로그인 처리
        return issueTokens(newMember);
    }

    // 네이티브 앱 소셜 로그인
    @Override
    @Transactional
    public NativeSocialLoginResponse nativeSocialLogin(NativeSocialLoginRequest request) {
        SocialUserInfo userInfo = socialTokenVerifier.verify(request);

        if (userInfo.providerId() == null || userInfo.providerId().isBlank()) {
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }

        // 기존 회원은 소셜 계정으로 식별한다 — 이메일 제공에 동의하지 않아도 로그인할 수 있다
        Optional<Member> existing = memberRepository.findBySocialAccount(userInfo.provider(), userInfo.providerId());
        if (existing.isPresent()) {
            LoginResponse tokens = issueTokens(existing.get());
            return NativeSocialLoginResponse.loginSuccess(tokens.getAccessToken(), tokens.getRefreshToken());
        }

        // 신규 가입에는 이메일이 필요하다
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            throw new ServiceException(ReturnCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }

        // 신규 회원 → ticket 발급
        String ticket = oAuthTicketService.issue(
                new OAuthTicketPayload(userInfo.email(), userInfo.provider(), userInfo.providerId()),
                Duration.ofMinutes(ticketTtlMinutes)
        );
        return NativeSocialLoginResponse.signupRequired(ticket);
    }

    // 이메일 로그인 (개발/스테이징 전용)
    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest loginRequest) {
        Member member = memberRepository.findByEmailBlindIndex(encryptionProperties.generateBlindIndex(loginRequest.getEmail()))
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        return issueTokens(member);
    }

    // 소셜 계정 로그인
    @Override
    @Transactional(readOnly = true)
    public LoginResponse loginBySocialAccount(Member.OAuthProvider provider, String providerId) {
        Member member = memberRepository.findBySocialAccount(provider, providerId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        return issueTokens(member);
    }

    // 토큰 발급 + Refresh Token 저장 (subject/키 모두 memberId 기준)
    private LoginResponse issueTokens(Member member) {
        String accessToken = jwtTokenProvider.generateToken(member.getId(), "ACCESS", accessTokenExpiration);
        String refreshToken = jwtTokenProvider.generateToken(member.getId(), "REFRESH", refreshTokenExpiration);
        refreshTokenService.saveRefreshToken(member.getId(), refreshToken, refreshTokenExpiration);
        return new LoginResponse(accessToken, refreshToken);
    }

    // 로그아웃
    @Override
    public void logout(MemberPrincipal currentUser, String accessToken) {
        // Redis에서 Refresh Token 삭제
        refreshTokenService.deleteRefreshToken(currentUser.getId());
        // access 즉시 차단(deny-list)
        if (accessToken != null && jwtTokenProvider.validateAccessToken(accessToken)) {
            String jti = jwtTokenProvider.getJti(accessToken);
            long ttlSec = jwtTokenProvider.getRemainingSeconds(accessToken);
            if (ttlSec > 0) {
                accessTokenDenyListService.deny(jti, Duration.ofSeconds(ttlSec));
            }
        }
        // WS 모두 종료 (4401: Unauthorized/Logged out)
        wsSessionRegistry.closeAll(currentUser.getId(), new CloseStatus(4401, "Logged out"));
    }

    // 회원 탈퇴 시 인증 정리 (logout + FCM 토큰 삭제)
    @Override
    public void cleanUpOnWithdraw(MemberPrincipal currentUser, String accessToken) {
        logout(currentUser, accessToken);
        fcmTokenService.deleteFcmToken(currentUser.getId());
    }

    // accessToken 재발급
    @Override
    public LoginResponse refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ServiceException(ReturnCode.UNAUTHORIZED);
        }
        if (refreshToken.startsWith("Bearer ")) {
            refreshToken = refreshToken.substring(7);
        }
        // 1) 서명/만료 검증 (여기서 만료면 바로 컷)
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new ServiceException(ReturnCode.TOKEN_EXPIRED);
        }
        // 2) refreshToken에서 사용자 식별자 추출
        Long memberId = jwtTokenProvider.getMemberIdFromToken(refreshToken);
        String storedRefreshToken = refreshTokenService.getRefreshToken(memberId);
        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
        if (!jwtTokenProvider.validateRefreshToken(storedRefreshToken)) {
            throw new ServiceException(ReturnCode.TOKEN_EXPIRED);
        }
        String newAccessToken = jwtTokenProvider.generateToken(memberId, "ACCESS", accessTokenExpiration);
        return new LoginResponse(newAccessToken, storedRefreshToken);
    }
}
