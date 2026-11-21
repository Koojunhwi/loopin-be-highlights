package com.loopone.loopinbe.global.security;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Getter
@AllArgsConstructor
public class MemberPrincipal implements UserDetails {
    private final Long id;
    private final String email;
    private final String password;
    private final String nickname;
    private final String phone;
    private final Member.Gender gender;
    private final LocalDate birthday;
    private final String profileImageUrl;
    private final Member.State state;
    private final Member.MemberRole role;
    private final Member.OAuthProvider provider;
    private final String providerId;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    public static MemberPrincipal from(Member member) {
        return new MemberPrincipal(
                member.getId(),
                member.getEmail(),
                member.getPassword(),
                member.getNickname(),
                member.getPhone(),
                member.getGender(),
                member.getBirthday(),
                member.getProfileImageUrl(),
                member.getState(),
                member.getRole(),
                member.getOAuthProvider(),
                member.getProviderId()
        );
    }
}
