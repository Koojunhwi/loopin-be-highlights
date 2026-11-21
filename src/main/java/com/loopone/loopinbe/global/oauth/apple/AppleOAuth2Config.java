package com.loopone.loopinbe.global.oauth.apple;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Configuration
@RequiredArgsConstructor
public class AppleOAuth2Config {
    private final AppleClientSecretGenerator appleClientSecretGenerator;

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.addParametersConverter(request -> {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            if ("apple".equals(request.getClientRegistration().getRegistrationId())) {
                String clientId = request.getClientRegistration().getClientId();
                params.set(OAuth2ParameterNames.CLIENT_ID, clientId);
                params.set(OAuth2ParameterNames.CLIENT_SECRET, appleClientSecretGenerator.generate(clientId));
            }
            return params;
        });
        return client;
    }
}
