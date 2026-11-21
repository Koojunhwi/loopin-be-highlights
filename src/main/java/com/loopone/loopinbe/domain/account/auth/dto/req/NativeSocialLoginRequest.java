package com.loopone.loopinbe.domain.account.auth.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NativeSocialLoginRequest(
        @NotNull(message = "provider는 필수입니다.")
        Provider provider,

        @NotBlank(message = "accessToken은 필수입니다.")
        String accessToken
) {
    public enum Provider {
        GOOGLE, KAKAO, NAVER, APPLE
    }
}
