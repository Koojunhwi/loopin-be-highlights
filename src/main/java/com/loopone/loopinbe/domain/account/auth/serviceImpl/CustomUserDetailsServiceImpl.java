package com.loopone.loopinbe.domain.account.auth.serviceImpl;

import com.loopone.loopinbe.domain.account.auth.service.MemberDetailsService;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsServiceImpl implements MemberDetailsService {
    private final MemberRepository memberRepository;
    private final EncryptionProperties encryptionProperties;
    @Value("${security.auth.dummy-password:{noop}social-user}")
    private String dummyPassword; // 하드코딩 회피: 설정/환경변수로 주입

    // JWT 인증 경로: subject(memberId)로 조회
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserById(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + memberId));
        return toPrincipal(member);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Member member = memberRepository.findByEmailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));
        return toPrincipal(member);
    }

    private MemberPrincipal toPrincipal(Member member) {
        // 비밀번호 보정: null/empty 방지
        String password = member.getPassword();
        if (!StringUtils.hasText(password)) {
            password = dummyPassword; // 더미값 (null/empty 금지)
        }
        else if (!password.startsWith("{")) {
            password = "{noop}" + password; // 비암호화 평문이 저장되어 있는 특수 케이스라면 명시적으로 noop
        }
        return new MemberPrincipal(
                member.getId(),
                member.getEmail(),
                password,
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
