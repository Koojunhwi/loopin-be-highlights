package com.loopone.loopinbe.domain.account.auth.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.auth.dto.req.LoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.req.NativeSocialLoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.dto.res.NativeSocialLoginResponse;
import com.loopone.loopinbe.domain.account.auth.serviceImpl.AuthServiceImpl;
import com.loopone.loopinbe.domain.account.member.dto.req.MemberCreateRequest;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberFollowRepository;
import com.loopone.loopinbe.domain.account.member.repository.MemberFollowReqRepository;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.domain.account.member.service.MemberService;
import com.loopone.loopinbe.domain.account.oauth.ticket.dto.OAuthTicketPayload;
import com.loopone.loopinbe.domain.account.oauth.ticket.service.OAuthTicketService;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomService;
import com.loopone.loopinbe.domain.loop.loop.service.LoopService;
import com.loopone.loopinbe.domain.team.team.service.TeamService;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.loopone.loopinbe.global.kafka.event.notification.NotificationEventPublisher;
import com.loopone.loopinbe.global.s3.S3Service;
import com.loopone.loopinbe.global.security.JwtTokenProvider;
import com.loopone.loopinbe.global.encryption.EmailLocalPartEncryptor;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.global.webSocket.util.WsSessionRegistry;
import com.loopone.loopinbe.support.TestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "custom.accessToken.expiration=30m",
        "custom.refreshToken.expiration=30d"
})
@Import({
        TestContainersConfig.class,
        EncryptionProperties.class,
        EmailLocalPartEncryptor.class,
        AuthServiceImpl.class
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthServiceTest {
    // ===== Real Repositories =====
    @Autowired MemberRepository memberRepository;
    @Autowired MemberFollowReqRepository memberFollowReqRepository;
    @Autowired MemberFollowRepository memberFollowRepository;
    @Autowired EncryptionProperties encryptionProperties;

    // ===== SUT =====
    @Autowired AuthServiceImpl authService;

    // ===== External boundaries (mock) =====
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean AccessTokenDenyListService accessTokenDenyListService;
    @MockitoBean WsSessionRegistry wsSessionRegistry;
    @MockitoBean OAuthTicketService oAuthTicketService;
    @MockitoBean com.loopone.loopinbe.domain.fcm.service.FcmTokenService fcmTokenService;
    @MockitoBean SocialTokenVerifier socialTokenVerifier;

    // MemberServiceImpl이 필요로 하는 외부 경계들 (MemberServiceTest와 동일)
    @MockitoBean S3Service s3Service;
    @MockitoBean MemberService memberService;
    @MockitoBean ChatRoomService chatRoomService;
    @MockitoBean TeamService teamService;
    @MockitoBean LoopService loopService;
    @MockitoBean NotificationEventPublisher notificationEventPublisher;

    @AfterEach
    void cleanup() {
        memberFollowRepository.deleteAll();
        memberFollowReqRepository.deleteAll();
        memberRepository.deleteAll();
        // refreshTokenService 등은 mock이라 cleanup 불필요
    }

    // ===== Helpers =====
    // 소셜 계정(provider + providerId)이 unique이므로 providerId는 이메일에서 파생시킨다
    private Member persistMember(String email, String nickname) {
        return persistMember(email, nickname, Member.OAuthProvider.GOOGLE, "pid-" + email);
    }

    private Member persistMember(String email, String nickname, Member.OAuthProvider provider, String providerId) {
        Member m = Member.builder()
                .email(email)
                .emailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .nickname(nickname)
                .profileImageUrl(null)
                .state(Member.State.NORMAL)
                .role(Member.MemberRole.ROLE_USER)
                .oAuthProvider(provider)
                .providerId(providerId)
                .build();
        return memberRepository.saveAndFlush(m);
    }

    // memberId 기준으로 토큰이 발급되도록 stub
    private void givenTokensFor(Long memberId) {
        given(jwtTokenProvider.generateToken(eq(memberId), eq("ACCESS"), any(Duration.class)))
                .willReturn("access-token-123");
        given(jwtTokenProvider.generateToken(eq(memberId), eq("REFRESH"), any(Duration.class)))
                .willReturn("refresh-token-456");
    }

    private MemberPrincipal cu(Member m) {
        return new MemberPrincipal(
                m.getId(),
                m.getEmail(),
                null,
                m.getNickname(),
                null,
                null,
                null,
                m.getProfileImageUrl(),
                m.getState(),
                m.getRole(),
                m.getOAuthProvider(),
                m.getProviderId()
        );
    }

    // =========================================================
    // login (개발용 이메일 로그인)
    // =========================================================
    @Nested
    class Login {

        @Test
        @DisplayName("성공: 이메일로 멤버 조회 후 memberId 기준으로 토큰 발급 + Redis 저장(memberId key)")
        void success() {
            // given
            String email = "jun@loop.in";
            Member member = persistMember(email, "jun");
            givenTokensFor(member.getId());
            LoginRequest req = LoginRequest.builder().email(email).build();

            // when
            LoginResponse resp = authService.login(req);

            // then
            assertThat(resp.getAccessToken()).isEqualTo("access-token-123");
            assertThat(resp.getRefreshToken()).isEqualTo("refresh-token-456");
            verify(jwtTokenProvider).generateToken(eq(member.getId()), eq("ACCESS"), any(Duration.class));
            verify(jwtTokenProvider).generateToken(eq(member.getId()), eq("REFRESH"), any(Duration.class));
            verify(refreshTokenService).saveRefreshToken(eq(member.getId()), eq("refresh-token-456"), any(Duration.class));
        }

        @Test
        @DisplayName("실패: 멤버가 없으면 USER_NOT_FOUND")
        void userNotFound() {
            // given
            LoginRequest req = LoginRequest.builder().email("no@loop.in").build();

            // when & then
            assertThatThrownBy(() -> authService.login(req))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining(ReturnCode.USER_NOT_FOUND.getMessage());

            verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), any(Duration.class));
            verify(refreshTokenService, never()).saveRefreshToken(anyLong(), anyString(), any(Duration.class));
        }
    }

    // =========================================================
    // loginBySocialAccount
    // =========================================================
    @Nested
    class LoginBySocialAccount {

        @Test
        @DisplayName("성공: provider+providerId로 멤버를 찾아 토큰 발급")
        void success() {
            Member member = persistMember("kakao@loop.in", "kakao", Member.OAuthProvider.KAKAO, "kakao-1");
            givenTokensFor(member.getId());

            LoginResponse resp = authService.loginBySocialAccount(Member.OAuthProvider.KAKAO, "kakao-1");

            assertThat(resp.getAccessToken()).isEqualTo("access-token-123");
            verify(refreshTokenService).saveRefreshToken(eq(member.getId()), eq("refresh-token-456"), any(Duration.class));
        }

        @Test
        @DisplayName("같은 providerId라도 provider가 다르면 다른 계정이라 USER_NOT_FOUND")
        void differentProviderIsDifferentAccount() {
            persistMember("kakao@loop.in", "kakao", Member.OAuthProvider.KAKAO, "same-id");

            assertThatThrownBy(() -> authService.loginBySocialAccount(Member.OAuthProvider.NAVER, "same-id"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining(ReturnCode.USER_NOT_FOUND.getMessage());
        }
    }

    // =========================================================
    // nativeSocialLogin
    // =========================================================
    @Nested
    class NativeSocialLogin {

        private NativeSocialLoginRequest request() {
            return new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.KAKAO, "social-access-token");
        }

        @Test
        @DisplayName("기존 회원: 소셜에서 이메일을 받지 못해도 provider+providerId로 로그인된다")
        void existingMemberLoginsWithoutEmail() {
            // given: 이메일 없이 응답하는 소셜 계정 + 이미 가입된 회원
            Member member = persistMember("kakao@loop.in", "kakao", Member.OAuthProvider.KAKAO, "kakao-1");
            given(socialTokenVerifier.verify(any(NativeSocialLoginRequest.class)))
                    .willReturn(new SocialTokenVerifier.SocialUserInfo(null, Member.OAuthProvider.KAKAO, "kakao-1"));
            givenTokensFor(member.getId());

            // when
            NativeSocialLoginResponse resp = authService.nativeSocialLogin(request());

            // then
            assertThat(resp.status()).isEqualTo("LOGIN_SUCCESS");
            assertThat(resp.accessToken()).isEqualTo("access-token-123");
            verify(oAuthTicketService, never()).issue(any(), any());
        }

        @Test
        @DisplayName("신규 회원: 이메일이 없으면 SOCIAL_EMAIL_NOT_PROVIDED")
        void newMemberRequiresEmail() {
            given(socialTokenVerifier.verify(any(NativeSocialLoginRequest.class)))
                    .willReturn(new SocialTokenVerifier.SocialUserInfo(null, Member.OAuthProvider.KAKAO, "unknown-1"));

            assertThatThrownBy(() -> authService.nativeSocialLogin(request()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining(ReturnCode.SOCIAL_EMAIL_NOT_PROVIDED.getMessage());

            verify(oAuthTicketService, never()).issue(any(), any());
        }

        @Test
        @DisplayName("신규 회원: 이메일이 있으면 ticket 발급")
        void newMemberIssuesTicket() {
            given(socialTokenVerifier.verify(any(NativeSocialLoginRequest.class)))
                    .willReturn(new SocialTokenVerifier.SocialUserInfo("new@loop.in", Member.OAuthProvider.KAKAO, "kakao-new"));
            given(oAuthTicketService.issue(any(OAuthTicketPayload.class), any(Duration.class))).willReturn("ticket-xyz");

            NativeSocialLoginResponse resp = authService.nativeSocialLogin(request());

            assertThat(resp.status()).isEqualTo("SIGNUP_REQUIRED");
            assertThat(resp.ticket()).isEqualTo("ticket-xyz");
        }

        @Test
        @DisplayName("이미 가입된 이메일이라도 provider가 다르면 신규 가입 절차를 탄다")
        void sameEmailDifferentProviderIsNewSignup() {
            persistMember("shared@loop.in", "googleUser", Member.OAuthProvider.GOOGLE, "google-1");
            given(socialTokenVerifier.verify(any(NativeSocialLoginRequest.class)))
                    .willReturn(new SocialTokenVerifier.SocialUserInfo("shared@loop.in", Member.OAuthProvider.KAKAO, "kakao-1"));
            given(oAuthTicketService.issue(any(OAuthTicketPayload.class), any(Duration.class))).willReturn("ticket-shared");

            NativeSocialLoginResponse resp = authService.nativeSocialLogin(request());

            assertThat(resp.status()).isEqualTo("SIGNUP_REQUIRED");
            assertThat(resp.ticket()).isEqualTo("ticket-shared");
        }

        @Test
        @DisplayName("providerId가 없으면 INVALID_AUTH_TOKEN")
        void missingProviderId() {
            given(socialTokenVerifier.verify(any(NativeSocialLoginRequest.class)))
                    .willReturn(new SocialTokenVerifier.SocialUserInfo("a@loop.in", Member.OAuthProvider.KAKAO, null));

            assertThatThrownBy(() -> authService.nativeSocialLogin(request()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining(ReturnCode.INVALID_AUTH_TOKEN.getMessage());
        }
    }

    // =========================================================
    // signUpAndLogin
    // =========================================================
    @Nested
    class SignUpAndLogin {

        @Test
        @DisplayName("성공: ticket consume -> regularSignUp 저장 -> 저장된 회원 id로 토큰 발급")
        void success() {
            // given
            String ticket = "ticket-abc";
            String email = "new@loop.in";
            String nickname = "newNick";
            OAuthTicketPayload payload = mock(OAuthTicketPayload.class);
            given(payload.email()).willReturn(email);
            given(payload.provider()).willReturn(Member.OAuthProvider.GOOGLE);
            given(payload.providerId()).willReturn("pid-x");
            given(oAuthTicketService.consume(ticket)).willReturn(payload);
            given(memberService.regularSignUp(any(MemberCreateRequest.class)))
                    .willAnswer(inv -> {
                        MemberCreateRequest req = inv.getArgument(0, MemberCreateRequest.class);

                        Member m = Member.builder()
                                .email(req.getEmail())
                                .emailBlindIndex(encryptionProperties.generateBlindIndex(req.getEmail()))
                                .nickname(req.getNickname())
                                .profileImageUrl(null)
                                .state(Member.State.NORMAL)
                                .role(Member.MemberRole.ROLE_USER)
                                .oAuthProvider(req.getProvider())
                                .providerId(req.getProviderId())
                                .build();

                        Member saved = memberRepository.saveAndFlush(m);
                        givenTokensFor(saved.getId());
                        return saved;
                    });

            // when
            LoginResponse resp = authService.signUpAndLogin(nickname, ticket);

            // then
            assertThat(resp.getAccessToken()).isEqualTo("access-token-123");
            assertThat(resp.getRefreshToken()).isEqualTo("refresh-token-456");

            // 회원이 실제 저장됐는지
            verify(memberService).regularSignUp(argThat(req ->
                    req.getEmail().equals(email)
                            && req.getNickname().equals(nickname)
                            && req.getProvider() == Member.OAuthProvider.GOOGLE
                            && req.getProviderId().equals("pid-x")
            ));
            verify(oAuthTicketService).consume(ticket);
            verify(refreshTokenService).saveRefreshToken(anyLong(), eq("refresh-token-456"), any(Duration.class));
        }
    }

    // =========================================================
    // logout
    // =========================================================
    @Nested
    class Logout {

        @Test
        @DisplayName("성공: RefreshToken 삭제(memberId) + access deny-list 등록 + WS 세션 종료")
        void success() {
            // given
            Member m = persistMember("jun@loop.in", "jun");
            MemberPrincipal currentUser = cu(m);
            String accessToken = "access-token-123";
            given(jwtTokenProvider.validateAccessToken(accessToken)).willReturn(true);
            given(jwtTokenProvider.getJti(accessToken)).willReturn("jti-123");
            given(jwtTokenProvider.getRemainingSeconds(accessToken)).willReturn(3600L);

            // when
            authService.logout(currentUser, accessToken);

            // then
            verify(refreshTokenService).deleteRefreshToken(currentUser.getId());
            verify(jwtTokenProvider).validateAccessToken(accessToken);
            verify(jwtTokenProvider).getJti(accessToken);
            verify(jwtTokenProvider).getRemainingSeconds(accessToken);
            verify(accessTokenDenyListService).deny(eq("jti-123"), eq(Duration.ofSeconds(3600L)));
            verify(wsSessionRegistry).closeAll(eq(currentUser.getId()),
                    argThat(status -> status.getCode() == 4401));
        }

        @Test
        @DisplayName("accessToken이 null이면 deny-list 로직은 타지 않음")
        void accessTokenNull() {
            // given
            Member m = persistMember("jun@loop.in", "jun");
            MemberPrincipal currentUser = cu(m);

            // when
            authService.logout(currentUser, null);

            // then
            verify(refreshTokenService).deleteRefreshToken(currentUser.getId());
            verify(jwtTokenProvider, never()).validateAccessToken(anyString());
            verify(accessTokenDenyListService, never()).deny(anyString(), any(Duration.class));
            verify(wsSessionRegistry).closeAll(eq(currentUser.getId()), argThat(status -> status.getCode() == 4401));
        }

        @Test
        @DisplayName("accessToken이 유효하지 않으면 deny-list에 추가하지 않음")
        void invalidAccessToken() {
            // given
            Member m = persistMember("jun@loop.in", "jun");
            MemberPrincipal currentUser = cu(m);
            String accessToken = "invalid-token";
            given(jwtTokenProvider.validateAccessToken(accessToken)).willReturn(false);

            // when
            authService.logout(currentUser, accessToken);

            // then
            verify(refreshTokenService).deleteRefreshToken(currentUser.getId());
            verify(jwtTokenProvider).validateAccessToken(accessToken);
            verify(accessTokenDenyListService, never()).deny(anyString(), any(Duration.class));
            verify(wsSessionRegistry).closeAll(eq(currentUser.getId()), argThat(status -> status.getCode() == 4401));
        }
    }

    // =========================================================
    // cleanUpOnWithdraw
    // =========================================================
    @Nested
    class CleanUpOnWithdraw {

        @Test
        @DisplayName("성공: logout 로직 수행 + FCM 토큰 삭제")
        void success() {
            // given
            Member m = persistMember("jun@loop.in", "jun");
            MemberPrincipal currentUser = cu(m);
            String accessToken = "access-token-123";
            given(jwtTokenProvider.validateAccessToken(accessToken)).willReturn(true);
            given(jwtTokenProvider.getJti(accessToken)).willReturn("jti-123");
            given(jwtTokenProvider.getRemainingSeconds(accessToken)).willReturn(3600L);

            // when
            authService.cleanUpOnWithdraw(currentUser, accessToken);

            // then - logout 로직 검증
            verify(refreshTokenService).deleteRefreshToken(currentUser.getId());
            verify(accessTokenDenyListService).deny(eq("jti-123"), eq(Duration.ofSeconds(3600L)));
            verify(wsSessionRegistry).closeAll(eq(currentUser.getId()),
                    argThat(status -> status.getCode() == 4401));
            // FCM 토큰 삭제 검증
            verify(fcmTokenService).deleteFcmToken(currentUser.getId());
        }
    }

    // =========================================================
    // refreshToken
    // =========================================================
    @Nested
    class RefreshToken {

        @Test
        @DisplayName("성공: Bearer 제거 -> validate -> memberId 추출 -> 저장된 토큰과 일치 -> 새 Access 발급")
        void success() {
            // given
            String raw = "refresh-token-456";
            String bearer = "Bearer " + raw;
            Long memberId = 42L;
            given(jwtTokenProvider.validateRefreshToken(raw)).willReturn(true);
            given(jwtTokenProvider.getMemberIdFromToken(raw)).willReturn(memberId);
            given(refreshTokenService.getRefreshToken(memberId)).willReturn(raw);
            given(jwtTokenProvider.generateToken(eq(memberId), eq("ACCESS"), any(Duration.class))).willReturn("new-access-token");

            // when
            LoginResponse resp = authService.refreshToken(bearer);

            // then
            assertThat(resp.getAccessToken()).isEqualTo("new-access-token");
            assertThat(resp.getRefreshToken()).isEqualTo(raw);
            verify(jwtTokenProvider, times(2)).validateRefreshToken(raw); // 2번 호출이 맞음
            verify(jwtTokenProvider).getMemberIdFromToken(raw);
            verify(refreshTokenService).getRefreshToken(memberId);
            verify(jwtTokenProvider).generateToken(eq(memberId), eq("ACCESS"), any(Duration.class));
        }

        @Test
        @DisplayName("실패: refreshToken이 null/blank면 예외")
        void missing() {
            assertThatThrownBy(() -> authService.refreshToken("  "))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("인증이 필요합니다.");
        }

        @Test
        @DisplayName("실패: 1차 validateRefreshToken 실패면 예외")
        void invalidOrExpiredAtFirstValidation() {
            String raw = "refresh-token-456";
            given(jwtTokenProvider.validateRefreshToken(raw)).willReturn(false);
            assertThatThrownBy(() -> authService.refreshToken(raw))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("만료된 토큰입니다.");

            verify(jwtTokenProvider).validateRefreshToken(raw);
            verify(refreshTokenService, never()).getRefreshToken(anyLong());
        }

        @Test
        @DisplayName("실패: 저장된 토큰이 없거나 값이 다르면 예외")
        void notMatched() {
            String raw = "refresh-token-456";
            Long memberId = 42L;
            given(jwtTokenProvider.validateRefreshToken(raw)).willReturn(true);
            given(jwtTokenProvider.getMemberIdFromToken(raw)).willReturn(memberId);
            given(refreshTokenService.getRefreshToken(memberId)).willReturn("other-token");
            assertThatThrownBy(() -> authService.refreshToken(raw))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("유효하지 않은 토큰입니다.");

            verify(jwtTokenProvider).validateRefreshToken(raw);
            verify(jwtTokenProvider).getMemberIdFromToken(raw);
            verify(refreshTokenService).getRefreshToken(memberId);
            verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("실패: 저장된 refreshToken이 2차 validate에서 만료면 예외")
        void expiredAtSecondValidation() {
            String raw = "refresh-token-456";
            Long memberId = 42L;
            given(jwtTokenProvider.validateRefreshToken(raw)).willReturn(true);
            given(jwtTokenProvider.getMemberIdFromToken(raw)).willReturn(memberId);
            given(refreshTokenService.getRefreshToken(memberId)).willReturn(raw);
            // 2번째 validate에서 false
            given(jwtTokenProvider.validateRefreshToken(raw))
                    .willReturn(true)   // 1차
                    .willReturn(false); // 2차
            assertThatThrownBy(() -> authService.refreshToken(raw))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("만료된 토큰입니다.");

            verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), any(Duration.class));
        }
    }
}
