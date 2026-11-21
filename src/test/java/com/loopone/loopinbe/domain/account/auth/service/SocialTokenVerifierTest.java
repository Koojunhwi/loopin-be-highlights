package com.loopone.loopinbe.domain.account.auth.service;

import com.loopone.loopinbe.domain.account.auth.dto.req.NativeSocialLoginRequest;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.util.LinkedMultiValueMap;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class SocialTokenVerifierTest {

    private static final String TEST_CLIENT_ID = "co.kr.loopin.service";
    private static final String TEST_BUNDLE_ID = "co.kr.loopin.app";

    private static final String GOOGLE_WEB_CLIENT_ID = "test-web-client.apps.googleusercontent.com";
    private static final String GOOGLE_IOS_CLIENT_ID = "test-ios-client.apps.googleusercontent.com";
    private static final List<String> GOOGLE_CLIENT_IDS = List.of(GOOGLE_WEB_CLIENT_ID, GOOGLE_IOS_CLIENT_ID);
    private static final long KAKAO_APP_ID = 1489218L;

    private SocialTokenVerifier verifier;
    private MockWebServer mockWebServer;
    private RSAKey rsaJWK;
    private RSAPrivateKey privateKey;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        rsaJWK = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("test-kid")
                .algorithm(JWSAlgorithm.RS256)
                .build();

        String jwksUrl = mockWebServer.url("/auth/keys").toString();
        verifier = new SocialTokenVerifier(
                new RestTemplateBuilder(), jwksUrl, TEST_CLIENT_ID, TEST_BUNDLE_ID,
                GOOGLE_CLIENT_IDS, KAKAO_APP_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    private String createAppleIdToken(String sub, String email, String aud, long expInMillis) throws Exception {
        JWSSigner signer = new RSASSASigner(privateKey);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://appleid.apple.com")
                .subject(sub)
                .audience(aud)
                .claim("email", email)
                .expirationTime(new Date(System.currentTimeMillis() + expInMillis))
                .issueTime(new Date())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-kid").build(),
                claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private void enqueueJwks() {
        JWKSet jwkSet = new JWKSet(rsaJWK.toPublicJWK());
        mockWebServer.enqueue(new MockResponse()
                .setBody(jwkSet.toString())
                .addHeader("Content-Type", "application/json"));
    }

    @Nested
    @DisplayName("verifyGoogle")
    class VerifyGoogle {

        private static final String ID_TOKEN =
                "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJnb29nbGUtc3ViIn0.c2lnbmF0dXJl";

        private SocialTokenVerifier createGoogleVerifier(RestTemplateBuilder builder) {
            return new SocialTokenVerifier(
                    builder, "https://apple.example/auth/keys", TEST_CLIENT_ID, TEST_BUNDLE_ID,
                    GOOGLE_CLIENT_IDS, KAKAO_APP_ID);
        }

        private NativeSocialLoginRequest googleRequest() {
            return new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.GOOGLE, ID_TOKEN);
        }

        private ResponseActions expectIdTokenRequest(MockRestServiceServer server) {
            return expectIdTokenRequest(server, ID_TOKEN);
        }

        private ResponseActions expectIdTokenRequest(MockRestServiceServer server, String token) {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("id_token", token);
            return server.expect(requestTo("https://oauth2.googleapis.com/tokeninfo"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(content().formData(form));
        }

        @Test
        @DisplayName("성공: 웹 audience의 ID 토큰을 쿼리 없는 POST 폼으로 보내고 Google 사용자 정보 추출")
        void success_idTokenPostForm() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withSuccess(
                    "{\"email\":\"user@google.com\",\"sub\":\"google-sub-123\",\"aud\":\"%s\"}".formatted(GOOGLE_WEB_CLIENT_ID), MediaType.APPLICATION_JSON));

            var result = googleVerifier.verify(googleRequest());

            assertThat(result).isEqualTo(new SocialTokenVerifier.SocialUserInfo(
                    "user@google.com", Member.OAuthProvider.GOOGLE, "google-sub-123"));
            server.verify();
        }

        @Test
        @DisplayName("성공: HTTP 클라이언트 메트릭의 uri는 토큰을 포함하지 않는 /tokeninfo")
        void success_metricsUriExcludesToken() {
            var meterRegistry = new SimpleMeterRegistry();
            var observationRegistry = ObservationRegistry.create();
            observationRegistry.observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
            var customizer = new MockServerRestTemplateCustomizer();
            var builder = new RestTemplateBuilder(customizer)
                    .additionalCustomizers(restTemplate -> restTemplate.setObservationRegistry(observationRegistry));
            var googleVerifier = createGoogleVerifier(builder);
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withSuccess(
                    "{\"email\":\"user@google.com\",\"sub\":\"google-sub-123\",\"aud\":\"%s\"}".formatted(GOOGLE_WEB_CLIENT_ID), MediaType.APPLICATION_JSON));

            googleVerifier.verify(googleRequest());

            var timers = meterRegistry.find("http.client.requests").timers();
            assertThat(timers).isNotEmpty();
            assertThat(timers).allSatisfy(timer -> {
                assertThat(timer.count()).isPositive();
                assertThat(timer.getId().getTag("uri"))
                        .isEqualTo("/tokeninfo")
                        .doesNotContain(ID_TOKEN);
            });
            server.verify();
        }

        @Test
        @DisplayName("성공: 허용 목록의 두 번째 iOS audience도 검증 통과")
        void success_iosAudience() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withSuccess(
                    "{\"email\":\"user@google.com\",\"sub\":\"google-sub-123\",\"aud\":\"%s\"}"
                            .formatted(GOOGLE_IOS_CLIENT_ID), MediaType.APPLICATION_JSON));

            var result = googleVerifier.verify(googleRequest());

            assertThat(result).isEqualTo(new SocialTokenVerifier.SocialUserInfo(
                    "user@google.com", Member.OAuthProvider.GOOGLE, "google-sub-123"));
            server.verify();
        }

        @Test
        @DisplayName("실패: aud가 허용 목록과 정확히 일치하지 않으면 예외이며 userinfo 미호출")
        void failure_audienceMismatch() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withSuccess(
                    "{\"email\":\"user@google.com\",\"sub\":\"google-sub-123\",\"aud\":\"%s\"}"
                            .formatted(GOOGLE_WEB_CLIENT_ID + ".other"), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> googleVerifier.verify(googleRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: aud가 없으면 예외이며 userinfo 미호출")
        void failure_missingAudience() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withSuccess(
                    "{\"email\":\"user@google.com\",\"sub\":\"google-sub-123\"}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> googleVerifier.verify(googleRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: tokeninfo 400이면 INVALID_AUTH_TOKEN 예외이며 userinfo 미호출")
        void failure_tokeninfoBadRequestWithoutFallback() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withBadRequest());

            assertThatThrownBy(() -> googleVerifier.verify(googleRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: tokeninfo 500이면 INVALID_AUTH_TOKEN 예외이며 userinfo 미호출")
        void failure_tokeninfoServerErrorWithoutFallback() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectIdTokenRequest(server).andRespond(withServerError());

            assertThatThrownBy(() -> googleVerifier.verify(googleRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: ey로 시작하지 않는 토큰도 POST 폼으로 보내고 400이면 예외이며 userinfo 미호출")
        void failure_nonJwtTokenBadRequestWithoutFallback() {
            var customizer = new MockServerRestTemplateCustomizer();
            var googleVerifier = createGoogleVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            String token = "google-access-token-not-jwt";
            var request = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.GOOGLE, token);
            expectIdTokenRequest(server, token).andRespond(withBadRequest());

            assertThatThrownBy(() -> googleVerifier.verify(request))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

    }

    @Nested
    @DisplayName("verifyKakao")
    class VerifyKakao {

        private static final String ACCESS_TOKEN = "kakao-access-token";

        private SocialTokenVerifier createKakaoVerifier(RestTemplateBuilder builder) {
            return new SocialTokenVerifier(
                    builder, "https://apple.example/auth/keys", TEST_CLIENT_ID, TEST_BUNDLE_ID,
                    GOOGLE_CLIENT_IDS, KAKAO_APP_ID);
        }

        private NativeSocialLoginRequest kakaoRequest() {
            return new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.KAKAO, ACCESS_TOKEN);
        }

        private ResponseActions expectAccessTokenInfoRequest(MockRestServiceServer server) {
            return server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN));
        }

        private ResponseActions expectUserMeRequest(MockRestServiceServer server) {
            return server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN));
        }

        @Test
        @DisplayName("성공: 앱 ID 확인 후 사용자 정보를 순서대로 조회하고 이메일 추출")
        void success_appIdMatches() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"expires_in\":7199,\"app_id\":%d}".formatted(KAKAO_APP_ID),
                    MediaType.APPLICATION_JSON));
            expectUserMeRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"kakao_account\":{\"email\":\"user@kakao.com\"}}", MediaType.APPLICATION_JSON));

            var result = kakaoVerifier.verify(kakaoRequest());

            assertThat(result).isEqualTo(new SocialTokenVerifier.SocialUserInfo(
                    "user@kakao.com", Member.OAuthProvider.KAKAO, "12345"));
            server.verify();
        }

        @Test
        @DisplayName("성공: kakao_account에 이메일이 없으면 email은 null")
        void success_missingEmail() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"expires_in\":7199,\"app_id\":%d}".formatted(KAKAO_APP_ID),
                    MediaType.APPLICATION_JSON));
            expectUserMeRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

            var result = kakaoVerifier.verify(kakaoRequest());

            assertThat(result).isEqualTo(new SocialTokenVerifier.SocialUserInfo(
                    null, Member.OAuthProvider.KAKAO, "12345"));
            server.verify();
        }

        @Test
        @DisplayName("성공: kakao_account가 없으면 email은 null")
        void success_missingKakaoAccount() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"expires_in\":7199,\"app_id\":%d}".formatted(KAKAO_APP_ID),
                    MediaType.APPLICATION_JSON));
            expectUserMeRequest(server).andRespond(withSuccess(
                    "{\"id\":12345}", MediaType.APPLICATION_JSON));

            var result = kakaoVerifier.verify(kakaoRequest());

            assertThat(result).isEqualTo(new SocialTokenVerifier.SocialUserInfo(
                    null, Member.OAuthProvider.KAKAO, "12345"));
            server.verify();
        }

        @Test
        @DisplayName("실패: app_id가 다르면 INVALID_AUTH_TOKEN 예외이며 user/me 미호출")
        void failure_appIdMismatch() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"expires_in\":7199,\"app_id\":%d}".formatted(KAKAO_APP_ID + 1),
                    MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> kakaoVerifier.verify(kakaoRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: app_id가 없으면 INVALID_AUTH_TOKEN 예외이며 user/me 미호출")
        void failure_missingAppId() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"expires_in\":7199}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> kakaoVerifier.verify(kakaoRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: access_token_info 401이면 INVALID_AUTH_TOKEN 예외이며 user/me 미호출")
        void failure_accessTokenInfoUnauthorized() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withUnauthorizedRequest());

            assertThatThrownBy(() -> kakaoVerifier.verify(kakaoRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

        @Test
        @DisplayName("실패: 앱 ID 검증 후 user/me 401이면 INVALID_AUTH_TOKEN 예외")
        void failure_userMeUnauthorized() {
            var customizer = new MockServerRestTemplateCustomizer();
            var kakaoVerifier = createKakaoVerifier(new RestTemplateBuilder(customizer));
            var server = customizer.getServer();
            expectAccessTokenInfoRequest(server).andRespond(withSuccess(
                    "{\"id\":12345,\"expires_in\":7199,\"app_id\":%d}".formatted(KAKAO_APP_ID),
                    MediaType.APPLICATION_JSON));
            expectUserMeRequest(server).andRespond(withUnauthorizedRequest());

            assertThatThrownBy(() -> kakaoVerifier.verify(kakaoRequest()))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getReturnCode()).isEqualTo(ReturnCode.INVALID_AUTH_TOKEN));
            server.verify();
        }

    }

    @Nested
    @DisplayName("verifyApple")
    class VerifyApple {

        @Test
        @DisplayName("성공: Bundle ID audience로 유효한 Apple ID Token에서 email, providerId 추출")
        void success_bundleId() throws Exception {
            enqueueJwks();
            String idToken = createAppleIdToken("apple-sub-123", "user@apple.com", TEST_BUNDLE_ID, 60_000);
            var request = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken);

            SocialTokenVerifier.SocialUserInfo result = verifier.verify(request);

            assertThat(result.email()).isEqualTo("user@apple.com");
            assertThat(result.provider()).isEqualTo(Member.OAuthProvider.APPLE);
            assertThat(result.providerId()).isEqualTo("apple-sub-123");
        }

        @Test
        @DisplayName("성공: Service ID audience로도 검증 통과")
        void success_clientId() throws Exception {
            enqueueJwks();
            String idToken = createAppleIdToken("apple-sub-456", "user2@apple.com", TEST_CLIENT_ID, 60_000);
            var request = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken);

            SocialTokenVerifier.SocialUserInfo result = verifier.verify(request);

            assertThat(result.email()).isEqualTo("user2@apple.com");
            assertThat(result.providerId()).isEqualTo("apple-sub-456");
        }

        @Test
        @DisplayName("실패: 잘못된 audience이면 INVALID_AUTH_TOKEN 예외")
        void invalidAudience() throws Exception {
            enqueueJwks();
            String idToken = createAppleIdToken("apple-sub-123", "user@apple.com", "com.other.app", 60_000);
            var request = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken);

            assertThatThrownBy(() -> verifier.verify(request))
                    .isInstanceOf(ServiceException.class);
        }

        @Test
        @DisplayName("실패: 만료된 토큰이면 INVALID_AUTH_TOKEN 예외")
        void expiredToken() throws Exception {
            enqueueJwks();
            String idToken = createAppleIdToken("apple-sub-123", "user@apple.com", TEST_BUNDLE_ID, -60_000);
            var request = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken);

            assertThatThrownBy(() -> verifier.verify(request))
                    .isInstanceOf(ServiceException.class);
        }

        @Test
        @DisplayName("실패: JWKS에 매칭되는 kid가 없으면 INVALID_AUTH_TOKEN 예외")
        void kidMismatch() throws Exception {
            RSAKey differentKey = new RSAKey.Builder(rsaJWK.toRSAPublicKey())
                    .keyID("different-kid")
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            JWKSet jwkSet = new JWKSet(differentKey.toPublicJWK());
            mockWebServer.enqueue(new MockResponse()
                    .setBody(jwkSet.toString())
                    .addHeader("Content-Type", "application/json"));

            String idToken = createAppleIdToken("apple-sub-123", "user@apple.com", TEST_BUNDLE_ID, 60_000);
            var request = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken);

            assertThatThrownBy(() -> verifier.verify(request))
                    .isInstanceOf(ServiceException.class);
        }

        @Test
        @DisplayName("성공: 두 번째 요청은 캐시된 JWKS를 사용 (네트워크 호출 1회)")
        void jwksCaching() throws Exception {
            enqueueJwks();
            // JWKS 응답은 1개만 enqueue — 두 번째 요청에서 네트워크 호출 시 빈 큐로 에러

            String idToken1 = createAppleIdToken("sub-1", "user1@apple.com", TEST_BUNDLE_ID, 60_000);
            String idToken2 = createAppleIdToken("sub-2", "user2@apple.com", TEST_BUNDLE_ID, 60_000);

            var req1 = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken1);
            var req2 = new NativeSocialLoginRequest(NativeSocialLoginRequest.Provider.APPLE, idToken2);

            SocialTokenVerifier.SocialUserInfo result1 = verifier.verify(req1);
            SocialTokenVerifier.SocialUserInfo result2 = verifier.verify(req2);

            assertThat(result1.providerId()).isEqualTo("sub-1");
            assertThat(result2.providerId()).isEqualTo("sub-2");
            assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        }
    }
}
