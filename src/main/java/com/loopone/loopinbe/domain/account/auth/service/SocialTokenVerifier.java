package com.loopone.loopinbe.domain.account.auth.service;

import com.loopone.loopinbe.domain.account.auth.dto.req.NativeSocialLoginRequest;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SocialTokenVerifier {
    private final RestTemplate restTemplate;
    private final String appleJwksUrl;
    private final String appleClientId;
    private final String appleBundleId;
    private final Set<String> googleClientIds; // Google ID 토큰 aud 허용 목록(앱의 웹·iOS 클라이언트 ID)
    private final long kakaoAppId;             // Kakao 토큰을 발급한 앱 ID

    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String KAKAO_TOKEN_INFO_URL = "https://kapi.kakao.com/v1/user/access_token_info";
    private static final String KAKAO_USER_ME_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String DEFAULT_APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final long JWKS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24시간

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private volatile JWKSet cachedJwkSet;
    private volatile long jwksCacheTimestamp;

    public SocialTokenVerifier(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${app.apple.jwks-url:" + DEFAULT_APPLE_JWKS_URL + "}") String appleJwksUrl,
            @Value("${app.apple.client-id:}") String appleClientId,
            @Value("${app.apple.bundle-id:}") String appleBundleId,
            @Value("${app.google.client-ids}") List<String> googleClientIds,
            @Value("${app.kakao.app-id}") long kakaoAppId) {
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
        this.appleJwksUrl = appleJwksUrl;
        this.appleClientId = appleClientId;
        this.appleBundleId = appleBundleId;
        this.googleClientIds = Set.copyOf(googleClientIds);
        this.kakaoAppId = kakaoAppId;
    }

    public record SocialUserInfo(
            String email,
            Member.OAuthProvider provider,
            String providerId
    ) {}

    public SocialUserInfo verify(NativeSocialLoginRequest request) {
        return switch (request.provider()) {
            case GOOGLE -> verifyGoogle(request.accessToken());
            case KAKAO -> verifyKakao(request.accessToken());
            case NAVER -> verifyNaver(request.accessToken());
            case APPLE -> verifyApple(request.accessToken());
        };
    }

    // Google은 ID 토큰만 받는다 — access token(userinfo)은 토큰을 발급받은 앱을 알려주지 않아 다른 앱의 토큰을 걸러낼 수 없다
    private SocialUserInfo verifyGoogle(String idToken) {
        // 토큰은 쿼리가 아니라 폼 본문으로 보낸다 — URL에 실으면 메트릭 uri 태그·Sentry 브레드크럼·예외 메시지에 그대로 남는다
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id_token", idToken);

        Map<String, Object> body;
        try {
            body = restTemplate.exchange(
                    GOOGLE_TOKENINFO_URL, HttpMethod.POST, new HttpEntity<>(form, headers), MAP_TYPE).getBody();
        } catch (RestClientException e) {
            log.warn("Google token verification failed: {}", e.getMessage());
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
        if (body == null) throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);

        // Google sub는 앱이 달라도 같은 값이라, aud를 보지 않으면 다른 앱에 발급된 토큰으로 그 사용자의 계정에 로그인된다
        Object aud = body.get("aud");
        if (!(aud instanceof String audience) || !googleClientIds.contains(audience)) {
            log.warn("Google ID token audience 불일치: {}", aud);
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }

        String email = (String) body.get("email");
        String sub = (String) body.get("sub");
        return new SocialUserInfo(email, Member.OAuthProvider.GOOGLE, sub);
    }

    private SocialUserInfo verifyKakao(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // 사용자 정보를 읽기 전에 토큰을 발급한 앱부터 확인한다 — 다른 앱에 발급된 토큰으로는 가입·로그인할 수 없다
            Map<String, Object> tokenInfo = restTemplate.exchange(
                    KAKAO_TOKEN_INFO_URL, HttpMethod.GET, entity, MAP_TYPE).getBody();
            Object appId = tokenInfo != null ? tokenInfo.get("app_id") : null;
            if (!(appId instanceof Number number) || number.longValue() != kakaoAppId) {
                log.warn("Kakao token app_id 불일치: {}", appId);
                throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
            }

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    KAKAO_USER_ME_URL, HttpMethod.GET, entity, MAP_TYPE);

            Map<String, Object> body = response.getBody();
            if (body == null) throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);

            String providerId = String.valueOf(body.get("id"));
            String email = null;

            Object kakaoAccountObj = body.get("kakao_account");
            if (kakaoAccountObj instanceof Map<?, ?> kakaoAccount) {
                Object emailObj = kakaoAccount.get("email");
                email = emailObj != null ? String.valueOf(emailObj) : null;
            }
            return new SocialUserInfo(email, Member.OAuthProvider.KAKAO, providerId);
        } catch (RestClientException e) {
            log.warn("Kakao token verification failed: {}", e.getMessage());
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
    }

    private SocialUserInfo verifyNaver(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://openapi.naver.com/v1/nid/me",
                    HttpMethod.GET, entity, MAP_TYPE);

            Map<String, Object> body = response.getBody();
            if (body == null) throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);

            Object respObj = body.get("response");
            if (respObj instanceof Map<?, ?> resp) {
                String providerId = resp.get("id") != null ? String.valueOf(resp.get("id")) : null;
                String email = resp.get("email") != null ? String.valueOf(resp.get("email")) : null;
                return new SocialUserInfo(email, Member.OAuthProvider.NAVER, providerId);
            }
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        } catch (RestClientException e) {
            log.warn("Naver token verification failed: {}", e.getMessage());
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
    }

    private JWKSet getAppleJwkSet() {
        JWKSet cached = this.cachedJwkSet;
        if (cached != null && (System.currentTimeMillis() - jwksCacheTimestamp) < JWKS_CACHE_TTL_MS) {
            return cached;
        }
        try {
            JWKSet jwkSet = JWKSet.load(new URL(appleJwksUrl));
            this.cachedJwkSet = jwkSet;
            this.jwksCacheTimestamp = System.currentTimeMillis();
            return jwkSet;
        } catch (Exception e) {
            log.warn("Apple JWKS 로드 실패: {}", e.getMessage());
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
    }

    private SocialUserInfo verifyApple(String identityToken) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(identityToken);
            String kid = signedJWT.getHeader().getKeyID();

            // Apple JWKS에서 공개키 조회 (캐싱)
            JWKSet jwkSet = getAppleJwkSet();
            JWK matchingKey = jwkSet.getKeyByKeyId(kid);
            if (matchingKey == null) {
                log.warn("Apple JWKS에서 kid={}에 해당하는 키를 찾을 수 없습니다.", kid);
                throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
            }

            // RSA 서명 검증
            RSAPublicKey publicKey = ((RSAKey) matchingKey).toRSAPublicKey();
            RSASSAVerifier rsaVerifier = new RSASSAVerifier(publicKey);
            if (!signedJWT.verify(rsaVerifier)) {
                log.warn("Apple ID Token 서명 검증 실패");
                throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
            }

            // Claims 검증
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (!APPLE_ISSUER.equals(claims.getIssuer())) {
                log.warn("Apple ID Token issuer 불일치: {}", claims.getIssuer());
                throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
            }
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                log.warn("Apple ID Token 만료");
                throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
            }

            // Audience 검증: Apple Client ID(Service ID) 또는 Bundle ID와 일치해야 함
            String aud = claims.getAudience() != null && !claims.getAudience().isEmpty()
                    ? claims.getAudience().get(0) : null;
            if (aud == null || (!aud.equals(appleClientId) && !aud.equals(appleBundleId))) {
                log.warn("Apple ID Token audience 불일치: {}", aud);
                throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
            }

            String sub = claims.getSubject();
            String email = (String) claims.getClaim("email");
            return new SocialUserInfo(email, Member.OAuthProvider.APPLE, sub);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Apple token verification failed: {}", e.getMessage());
            throw new ServiceException(ReturnCode.INVALID_AUTH_TOKEN);
        }
    }
}
