package com.loopone.loopinbe.domain.loop.ai.service;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.domain.loop.loop.entity.Loop;
import com.loopone.loopinbe.domain.loop.loop.entity.LoopRule;
import com.loopone.loopinbe.domain.loop.loop.enums.RepeatType;
import com.loopone.loopinbe.domain.loop.loop.repository.LoopRepository;
import com.loopone.loopinbe.domain.loop.loop.repository.LoopRuleRepository;
import com.loopone.loopinbe.domain.loop.loopChecklist.entity.LoopChecklist;
import com.loopone.loopinbe.domain.loop.loopChecklist.repository.LoopChecklistRepository;
import com.loopone.loopinbe.global.encryption.EmailLocalPartEncryptor;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.support.TestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({TestContainersConfig.class, EncryptionProperties.class, EmailLocalPartEncryptor.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LoopAIServiceTest {

    @Autowired LoopRepository loopRepository;
    @Autowired LoopRuleRepository loopRuleRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired LoopChecklistRepository loopChecklistRepository;
    @Autowired EntityManager entityManager;
    @Autowired EncryptionProperties encryptionProperties;

    @AfterEach
    void cleanup() {
        loopChecklistRepository.deleteAll();
        loopRepository.deleteAll();
        loopRuleRepository.deleteAll();
        memberRepository.deleteAll();
    }

    // ===== Helpers =====
    private Member persistMember() {
        String email = "test@loop.in";
        Member m = Member.builder()
                .email(email)
                .emailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .nickname("tester")
                .profileImageUrl(null)
                .state(Member.State.NORMAL)
                .role(Member.MemberRole.ROLE_USER)
                .oAuthProvider(Member.OAuthProvider.GOOGLE)
                .providerId("pid")
                .build();
        return memberRepository.saveAndFlush(m);
    }

    private LoopRule persistLoopRule(Member member) {
        LoopRule rule = LoopRule.builder()
                .member(member)
                .scheduleType(RepeatType.WEEKLY)
                .daysOfWeek(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
                .startDate(LocalDate.of(2026, 5, 1))
                .endDate(LocalDate.of(2026, 5, 31))
                .build();
        return loopRuleRepository.saveAndFlush(rule);
    }

    private Loop persistLoop(Member member, LoopRule rule, LocalDate date, String title) {
        Loop loop = Loop.builder()
                .member(member)
                .loopDate(date)
                .title(title)
                .completed(false)
                .loopRule(rule)
                .build();
        return loopRepository.saveAndFlush(loop);
    }

    private LoopChecklist persistChecklist(Loop loop, String content) {
        LoopChecklist cl = LoopChecklist.builder()
                .loop(loop)
                .content(content)
                .completed(false)
                .build();
        return loopChecklistRepository.saveAndFlush(cl);
    }

    @Nested
    @DisplayName("findNearestLoopIdByLoopRuleId + findByIdWithChecklistsAndRule (2단계 쿼리)")
    class TwoStepQuery {

        @Test
        @DisplayName("성공: 오늘 이후 가장 가까운 루프를 체크리스트와 함께 조회")
        void returnsNearestLoopWithChecklists() {
            var member = persistMember();
            var rule = persistLoopRule(member);

            var loopToday = persistLoop(member, rule, LocalDate.of(2026, 5, 4), "오늘 루프");
            persistChecklist(loopToday, "체크1");
            persistChecklist(loopToday, "체크2");

            var loopTomorrow = persistLoop(member, rule, LocalDate.of(2026, 5, 5), "내일 루프");
            persistChecklist(loopTomorrow, "체크3");

            entityManager.flush();
            entityManager.clear();

            // Step 1: ID 조회
            Optional<Long> loopId = loopRepository.findNearestLoopIdByLoopRuleId(
                    rule.getId(), LocalDate.of(2026, 5, 4));
            assertThat(loopId).isPresent();
            assertThat(loopId.get()).isEqualTo(loopToday.getId());

            // Step 2: Fetch join으로 상세 조회
            Optional<Loop> result = loopRepository.findByIdWithChecklistsAndRule(loopId.get());
            assertThat(result).isPresent();

            Loop loop = result.get();
            assertThat(loop.getTitle()).isEqualTo("오늘 루프");
            assertThat(loop.getLoopChecklists()).hasSize(2);
            assertThat(loop.getLoopRule()).isNotNull();
            assertThat(loop.getLoopRule().getScheduleType()).isEqualTo(RepeatType.WEEKLY);
        }

        @Test
        @DisplayName("성공: 오늘 이전의 루프만 있으면 빈 결과 반환")
        void returnsEmptyWhenAllLoopsInPast() {
            var member = persistMember();
            var rule = persistLoopRule(member);
            persistLoop(member, rule, LocalDate.of(2026, 5, 1), "과거 루프");

            Optional<Long> loopId = loopRepository.findNearestLoopIdByLoopRuleId(
                    rule.getId(), LocalDate.of(2026, 5, 4));
            assertThat(loopId).isEmpty();
        }

        @Test
        @DisplayName("성공: 체크리스트가 없는 루프도 정상 조회")
        void returnsLoopWithoutChecklists() {
            var member = persistMember();
            var rule = persistLoopRule(member);
            var loop = persistLoop(member, rule, LocalDate.of(2026, 5, 5), "빈 루프");

            entityManager.flush();
            entityManager.clear();

            Optional<Long> loopId = loopRepository.findNearestLoopIdByLoopRuleId(
                    rule.getId(), LocalDate.of(2026, 5, 4));
            assertThat(loopId).isPresent();

            Optional<Loop> result = loopRepository.findByIdWithChecklistsAndRule(loopId.get());
            assertThat(result).isPresent();
            assertThat(result.get().getLoopChecklists()).isEmpty();
        }
    }
}
