package com.loopone.loopinbe.global.oauth.user;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User baseUser = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId(); // google/kakao/naver
        Member.OAuthProvider provider = Member.OAuthProvider.from(registrationId);
        Map<String, Object> attrs = baseUser.getAttributes();
        String email = null;
        String providerId = null;
        switch (provider) {
            case GOOGLE -> {
                email = (String) attrs.get("email");
                providerId = (String) attrs.get("sub");
            }
            case KAKAO -> {
                Object idObj = attrs.get("id");
                providerId = idObj != null ? String.valueOf(idObj) : null;
                Object kakaoAccountObj = attrs.get("kakao_account");
                if (kakaoAccountObj instanceof Map<?, ?> kakaoAccount) {
                    Object emailObj = kakaoAccount.get("email");
                    email = emailObj != null ? String.valueOf(emailObj) : null;
                }
            }
            case NAVER -> {
                Object respObj = attrs.get("response");
                if (respObj instanceof Map<?, ?> resp) {
                    Object idObj = resp.get("id");
                    Object emailObj = resp.get("email");
                    providerId = idObj != null ? String.valueOf(idObj) : null;
                    email = emailObj != null ? String.valueOf(emailObj) : null;
                }
            }
            case APPLE -> {
                email = (String) attrs.get("email");
                providerId = (String) attrs.get("sub");
            }
            default -> { }
        }
        if (providerId == null || providerId.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_provider_id"), "providerId를 확인할 수 없습니다.");
        }
        // 이메일이 없어도 여기서 막지 않는다: 기존 회원은 provider+providerId로 로그인되고,
        // 신규 가입일 때만 SuccessHandler가 이메일을 요구한다
        return new CustomOAuth2User(baseUser.getAuthorities(), attrs, email, provider, providerId);
    }
}
