package com.loopone.loopinbe.domain.notification.setting.repository;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.notification.setting.entity.MemberNotificationSetting;
import com.loopone.loopinbe.global.encryption.EmailLocalPartEncryptor;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaAuditing
@Import({EncryptionProperties.class, EmailLocalPartEncryptor.class})
class MemberNotificationSettingRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MemberNotificationSettingRepository memberNotificationSettingRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private EncryptionProperties encryptionProperties;

    @Test
    @DisplayName("purgeForMembersDeletedBefore - 30일 초과 탈퇴 회원의 설정만 삭제하고 나머지는 남긴다")
    void purgeForMembersDeletedBefore() {
        // given: 40일 전 탈퇴한 회원 / 살아있는 회원
        Member withdrawn = persistMember("withdrawn@test.com", "Withdrawn");
        Member active = persistMember("active@test.com", "Active");

        em.persist(MemberNotificationSetting.createDefault(withdrawn.getId()));
        em.persist(MemberNotificationSetting.createDefault(active.getId()));
        em.flush();

        em.createNativeQuery("UPDATE member SET deleted_at = :deletedAt WHERE id = :id")
                .setParameter("deletedAt", Instant.now().minus(40, ChronoUnit.DAYS))
                .setParameter("id", withdrawn.getId())
                .executeUpdate();
        em.clear();

        // when
        int purged = memberNotificationSettingRepository
                .purgeForMembersDeletedBefore(Instant.now().minus(30, ChronoUnit.DAYS));

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(memberNotificationSettingRepository.findByMemberId(withdrawn.getId())).isEmpty();
        assertThat(memberNotificationSettingRepository.findByMemberId(active.getId())).isPresent();
    }

    @Test
    @DisplayName("purgeForMembersDeletedBefore - 30일 이내 탈퇴 회원의 설정은 유지된다")
    void purgeForMembersDeletedBefore_recentWithdrawalKept() {
        // given: 10일 전 탈퇴한 회원
        Member recent = persistMember("recent@test.com", "Recent");
        em.persist(MemberNotificationSetting.createDefault(recent.getId()));
        em.flush();

        em.createNativeQuery("UPDATE member SET deleted_at = :deletedAt WHERE id = :id")
                .setParameter("deletedAt", Instant.now().minus(10, ChronoUnit.DAYS))
                .setParameter("id", recent.getId())
                .executeUpdate();
        em.clear();

        // when
        int purged = memberNotificationSettingRepository
                .purgeForMembersDeletedBefore(Instant.now().minus(30, ChronoUnit.DAYS));

        // then
        assertThat(purged).isZero();
        assertThat(memberNotificationSettingRepository.findByMemberId(recent.getId())).isPresent();
    }

    private Member persistMember(String email, String nickname) {
        Member m = Member.builder()
                .email(email)
                .emailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .nickname(nickname)
                .state(Member.State.NORMAL)
                .role(Member.MemberRole.ROLE_USER)
                .build();
        em.persist(m);
        return m;
    }
}
