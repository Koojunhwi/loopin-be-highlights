package com.loopone.loopinbe.domain.account.auth.dto.res;

public record NativeSocialLoginResponse(
        String status,
        String accessToken,
        String refreshToken,
        String ticket
) {
    public static NativeSocialLoginResponse loginSuccess(String accessToken, String refreshToken) {
        return new NativeSocialLoginResponse("LOGIN_SUCCESS", accessToken, refreshToken, null);
    }

    public static NativeSocialLoginResponse signupRequired(String ticket) {
        return new NativeSocialLoginResponse("SIGNUP_REQUIRED", null, null, ticket);
    }
}
