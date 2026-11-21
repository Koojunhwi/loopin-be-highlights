package com.loopone.loopinbe.domain.account.member.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class MemberResponse {
    private Long id;
    private String email;
    private String nickname;
    private String profileImageUrl;
    @JsonIgnore
    private Instant createdAt;

    public MemberResponse(Long id, String email, String nickname, String profileImageUrl, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.createdAt = createdAt;
    }
}
