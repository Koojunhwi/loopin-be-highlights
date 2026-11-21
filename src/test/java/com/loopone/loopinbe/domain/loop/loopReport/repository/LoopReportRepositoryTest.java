package com.loopone.loopinbe.domain.loop.loopReport.repository;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({TestContainersConfig.class, EncryptionProperties.class, EmailLocalPartEncryptor.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("findRepeatLoopsByMemberAndDateBetween 쿼리 정합성")
class LoopReportRepositoryTest {

    @Autowired LoopRepository loopRepository;
    @Autowired LoopRuleRepository loopRuleRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired LoopChecklistRepository loopChecklistRepository;
    @Autowired EntityManager em;
    @Autowired EncryptionProperties encryptionProperties;

    private Member member;
    private LoopRule rule;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("test@loop.in")
                .emailBlindIndex(encryptionProperties.generateBlindIndex("test@loop.in"))
                .nickname("tester")
                .state(Member.State.NORMAL)
                .role(Member.MemberRole.ROLE_USER)
                .oAuthProvider(Member.OAuthProvider.GOOGLE)
                .providerId("pid")
                .build());

        rule = loopRuleRepository.saveAndFlush(LoopRule.builder()
                .member(member)
                .scheduleType(RepeatType.WEEKLY)
                .daysOfWeek(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .build());
    }

    @AfterEach
    void cleanup() {
        loopChecklistRepository.deleteAll();
        loopRepository.deleteAll();
        loopRuleRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private Loop persistLoop(String title, LocalDate date, LoopRule loopRule) {
        return loopRepository.saveAndFlush(Loop.builder()
                .member(member)
                .title(title)
                .loopDate(date)
                .completed(false)
                .loopRule(loopRule)
                .build());
    }

    // ===================== loopRule IS NOT NULL 필터 =====================

    @Nested
    @DisplayName("loopRule IS NOT NULL 필터")
    class LoopRuleFilter {

        @Test
        @DisplayName("loopRule이 null인 단일 루프는 조회에서 제외된다")
        void excludesSingleLoop() {
            LocalDate date = LocalDate.of(2026, 6, 10);
            persistLoop("반복 루프", date, rule);
            loopRepository.saveAndFlush(Loop.builder()
                    .member(member).title("단일 루프").loopDate(date)
                    .completed(false).loopRule(null).build());

            List<Loop> result = loopRepository.findRepeatLoopsByMemberAndDateBetween(
                    member.getId(), date, date);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("반복 루프");
        }
    }

    // ===================== 날짜 범위 경계값 =====================

    @Nested
    @DisplayName("날짜 범위 경계값 (BETWEEN)")
    class DateRangeBoundary {

        @Test
        @DisplayName("start, end 경계 날짜의 루프가 포함된다")
        void includesBoundaryDates() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 7);
            persistLoop("시작일", start, rule);
            persistLoop("종료일", end, rule);
            persistLoop("중간일", start.plusDays(3), rule);

            List<Loop> result = loopRepository.findRepeatLoopsByMemberAndDateBetween(
                    member.getId(), start, end);

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("start 하루 전, end 하루 후 루프는 제외된다")
        void excludesOutOfRange() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 7);
            persistLoop("범위 내", start.plusDays(1), rule);
            persistLoop("범위 전", start.minusDays(1), rule);
            persistLoop("범위 후", end.plusDays(1), rule);

            List<Loop> result = loopRepository.findRepeatLoopsByMemberAndDateBetween(
                    member.getId(), start, end);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("범위 내");
        }
    }

    // ===================== memberId 필터 =====================

    @Nested
    @DisplayName("memberId 필터")
    class MemberFilter {

        @Test
        @DisplayName("다른 멤버의 루프는 조회되지 않는다")
        void excludesOtherMemberLoops() {
            LocalDate date = LocalDate.of(2026, 6, 10);
            persistLoop("내 루프", date, rule);

            Member other = memberRepository.saveAndFlush(Member.builder()
                    .email("other@loop.in").emailBlindIndex(encryptionProperties.generateBlindIndex("other@loop.in"))
                    .nickname("other")
                    .state(Member.State.NORMAL).role(Member.MemberRole.ROLE_USER)
                    .oAuthProvider(Member.OAuthProvider.GOOGLE).providerId("pid2")
                    .build());
            LoopRule otherRule = loopRuleRepository.saveAndFlush(LoopRule.builder()
                    .member(other).scheduleType(RepeatType.WEEKLY)
                    .daysOfWeek(Set.of(DayOfWeek.FRIDAY))
                    .startDate(LocalDate.of(2026, 1, 1))
                    .endDate(LocalDate.of(2026, 12, 31))
                    .build());
            loopRepository.saveAndFlush(Loop.builder()
                    .member(other).title("남의 루프").loopDate(date)
                    .completed(false).loopRule(otherRule).build());

            List<Loop> result = loopRepository.findRepeatLoopsByMemberAndDateBetween(
                    member.getId(), date, date);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("내 루프");
        }
    }

    // ===================== EntityGraph fetch join 검증 =====================

    @Nested
    @DisplayName("EntityGraph fetch join 검증")
    class EntityGraphFetch {

        @Test
        @DisplayName("loopChecklists, loopRule, daysOfWeek이 em.clear() 후에도 접근 가능하다")
        void fetchesAssociationsWithoutLazyInit() {
            LocalDate date = LocalDate.of(2026, 6, 10);
            Loop loop = persistLoop("체크리스트 루프", date, rule);
            loopChecklistRepository.saveAndFlush(LoopChecklist.builder()
                    .loop(loop).content("할 일 1").completed(false).build());
            loopChecklistRepository.saveAndFlush(LoopChecklist.builder()
                    .loop(loop).content("할 일 2").completed(true).build());

            em.flush();
            em.clear();

            List<Loop> result = loopRepository.findRepeatLoopsByMemberAndDateBetween(
                    member.getId(), date, date);

            assertThat(result).hasSize(1);
            Loop fetched = result.get(0);
            // 카테시안 곱 없이 정확한 개수로 로딩되는지 검증
            assertThat(fetched.getLoopChecklists()).hasSize(2);
            assertThat(fetched.getLoopRule()).isNotNull();
            assertThat(fetched.getLoopRule().getDaysOfWeek()).containsExactlyInAnyOrder(
                    DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        }
    }

    // ===================== Soft Delete 필터 =====================

    @Nested
    @DisplayName("Soft Delete 필터 (@SQLRestriction)")
    class SoftDeleteFilter {

        @Test
        @DisplayName("soft delete된 루프는 조회에서 제외된다")
        void excludesSoftDeletedLoops() {
            LocalDate date = LocalDate.of(2026, 6, 10);
            persistLoop("정상 루프", date, rule);
            Loop deleted = persistLoop("삭제된 루프", date, rule);
            deleted.softDelete();
            loopRepository.saveAndFlush(deleted);

            em.flush();
            em.clear();

            List<Loop> result = loopRepository.findRepeatLoopsByMemberAndDateBetween(
                    member.getId(), date, date);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("정상 루프");
        }
    }
}
