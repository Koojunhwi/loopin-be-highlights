package com.loopone.loopinbe.global.initData.util;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class NotProdUtils {
    private final MemberRepository memberRepository;
    private final EncryptionProperties encryptionProperties;

    public Member getMemberByEmailOrThrow(String email) {
        return memberRepository.findByEmailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
    }
}
