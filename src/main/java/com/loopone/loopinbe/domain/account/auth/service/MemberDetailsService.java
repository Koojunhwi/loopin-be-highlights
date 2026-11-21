package com.loopone.loopinbe.domain.account.auth.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// JWT subject가 memberId이므로 id로 회원을 로드한다.
// UserDetailsService(username=email)는 Spring Security 기본 계약 유지를 위해 함께 구현한다.
public interface MemberDetailsService extends UserDetailsService {
    UserDetails loadUserById(Long memberId);
}
