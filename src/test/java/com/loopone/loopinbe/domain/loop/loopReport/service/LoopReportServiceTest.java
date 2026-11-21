package com.loopone.loopinbe.domain.loop.loopReport.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.loop.loop.dto.res.LoopDetailResponse;
import com.loopone.loopinbe.domain.loop.loop.entity.Loop;
import com.loopone.loopinbe.domain.loop.loop.entity.LoopRule;
import com.loopone.loopinbe.domain.loop.loop.enums.RepeatType;
import com.loopone.loopinbe.domain.loop.loop.mapper.LoopMapper;
import com.loopone.loopinbe.domain.loop.loop.repository.LoopRepository;
import com.loopone.loopinbe.domain.loop.loopChecklist.entity.LoopChecklist;
import com.loopone.loopinbe.domain.loop.loopReport.dto.res.LoopReportResponse;
import com.loopone.loopinbe.domain.loop.loopReport.enums.DetailReportState;
import com.loopone.loopinbe.domain.loop.loopReport.enums.ReportState;
import com.loopone.loopinbe.domain.loop.loopReport.messages.MonthReportMessages;
import com.loopone.loopinbe.domain.loop.loopReport.messages.WeekReportMessages;
import com.loopone.loopinbe.domain.loop.loopReport.serviceImpl.LoopReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(SpringExtension.class)
public class LoopReportServiceTest {

    @Mock
    private LoopRepository loopRepository;

    @Mock
    private LoopMapper loopMapper;

    @InjectMocks
    private LoopReportServiceImpl loopReportService;

    private static final LocalDate TODAY = LocalDate.now();
    private static final Instant OLDER = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Loop> EMPTY = Collections.emptyList();

    // ===================== Helper Methods =====================

    private MemberPrincipal user() {
        return new MemberPrincipal(
                1L, "jun@loop.in", null, "jun", "010-0000-0000",
                Member.Gender.MALE, LocalDate.of(2000, 1, 1), null,
                Member.State.NORMAL, Member.MemberRole.ROLE_USER,
                Member.OAuthProvider.GOOGLE, "pid"
        );
    }

    private LoopRule rule(Long id) {
        return LoopRule.builder()
                .id(id)
                .scheduleType(RepeatType.WEEKLY)
                .daysOfWeek(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .build();
    }

    /** 체크리스트 없음, completed=true → 달성률 100% */
    private Loop done(Long id, String title, LocalDate date, LoopRule rule) {
        return done(id, title, date, rule, Instant.now());
    }

    private Loop done(Long id, String title, LocalDate date, LoopRule rule, Instant created) {
        return Loop.builder()
                .id(id).title(title).loopDate(date)
                .completed(true)
                .loopChecklists(Collections.emptyList())
                .loopRule(rule).createdAt(created)
                .build();
    }

    /** 체크리스트 없음, completed=false → 달성률 0% */
    private Loop undone(Long id, String title, LocalDate date, LoopRule rule) {
        return undone(id, title, date, rule, Instant.now());
    }

    private Loop undone(Long id, String title, LocalDate date, LoopRule rule, Instant created) {
        return Loop.builder()
                .id(id).title(title).loopDate(date)
                .completed(false)
                .loopChecklists(Collections.emptyList())
                .loopRule(rule).createdAt(created)
                .build();
    }

    /** 체크리스트 n개 중 k개 완료 → round(k/n * 100)% */
    private Loop partial(Long id, String title, LocalDate date,
                         LoopRule rule, int n, int k) {
        return partial(id, title, date, rule, n, k, Instant.now());
    }

    private Loop partial(Long id, String title, LocalDate date,
                         LoopRule rule, int n, int k, Instant created) {
        Loop loop = Loop.builder()
                .id(id).title(title).loopDate(date)
                .completed(false).loopRule(rule).createdAt(created)
                .build();
        List<LoopChecklist> checklists = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            checklists.add(LoopChecklist.builder()
                    .id(id * 100L + i)
                    .content("c" + i)
                    .completed(i < k)
                    .loop(loop)
                    .build());
        }
        loop.setLoopChecklists(checklists);
        return loop;
    }

    /**
     * getLoopReport 내부에서 findRepeatLoopsByMemberAndDateBetween 를 3회 호출한다.
     * 1회: 최근 10일, 2회: 최근 7일, 3회: 이번 달
     */
    private void mock3(List<Loop> tenDay, List<Loop> sevenDay, List<Loop> month) {
        given(loopRepository.findRepeatLoopsByMemberAndDateBetween(eq(1L), any(), any()))
                .willReturn(tenDay)
                .willReturn(sevenDay)
                .willReturn(month);
    }

    private void stubMapper() {
        given(loopMapper.loopRuleToLoopRuleDTO(any())).willReturn(
                new LoopDetailResponse.LoopRuleDTO(
                        1L, RepeatType.WEEKLY,
                        List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    // ===================== ReportState 경계값 판정 =====================

    @Nested
    @DisplayName("ReportState 경계값 판정")
    class ReportStateBoundary {

        @Test
        @DisplayName("10일 평균 달성률 80% → GOOD (경계)")
        void avgPercent80_GOOD() {
            LoopRule r = rule(1L);
            // 5 loops: 4×100% + 1×0% = avg 80%
            List<Loop> tenDay = List.of(
                    done(1L, "L", TODAY.minusDays(1), r),
                    done(2L, "L", TODAY.minusDays(2), r),
                    done(3L, "L", TODAY.minusDays(3), r),
                    done(4L, "L", TODAY.minusDays(4), r),
                    undone(5L, "L", TODAY.minusDays(5), r));
            mock3(tenDay, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.loopReportState()).isEqualTo(ReportState.GOOD);
            assertThat(res.tenDayAvgPercent()).isEqualTo(80L);
        }

        @Test
        @DisplayName("10일 평균 달성률 60% → OK")
        void avgPercent60_OK() {
            LoopRule r = rule(1L);
            // 5 loops: 3×100% + 2×0% = avg 60%
            List<Loop> tenDay = List.of(
                    done(1L, "L", TODAY.minusDays(1), r),
                    done(2L, "L", TODAY.minusDays(2), r),
                    done(3L, "L", TODAY.minusDays(3), r),
                    undone(4L, "L", TODAY.minusDays(4), r),
                    undone(5L, "L", TODAY.minusDays(5), r));
            mock3(tenDay, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.loopReportState()).isEqualTo(ReportState.OK);
            assertThat(res.tenDayAvgPercent()).isEqualTo(60L);
        }

        @Test
        @DisplayName("10일 평균 달성률 정확히 50% → OK (경계)")
        void avgPercent50_OK_boundary() {
            LoopRule r = rule(1L);
            // 2 loops: 1×100% + 1×0% = avg 50%
            List<Loop> tenDay = List.of(
                    done(1L, "L", TODAY.minusDays(1), r),
                    undone(2L, "L", TODAY.minusDays(2), r));
            mock3(tenDay, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.loopReportState()).isEqualTo(ReportState.OK);
            assertThat(res.tenDayAvgPercent()).isEqualTo(50L);
        }

        @Test
        @DisplayName("10일 평균 달성률 40% → HARD")
        void avgPercent40_HARD() {
            LoopRule r = rule(1L);
            // 5 loops: 2×100% + 3×0% = avg 40%
            List<Loop> tenDay = List.of(
                    done(1L, "L", TODAY.minusDays(1), r),
                    done(2L, "L", TODAY.minusDays(2), r),
                    undone(3L, "L", TODAY.minusDays(3), r),
                    undone(4L, "L", TODAY.minusDays(4), r),
                    undone(5L, "L", TODAY.minusDays(5), r));
            mock3(tenDay, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.loopReportState()).isEqualTo(ReportState.HARD);
            assertThat(res.tenDayAvgPercent()).isEqualTo(40L);
        }

        @Test
        @DisplayName("10일 데이터 없음 → NONE + 사용자명 포함 메시지")
        void noData_NONE() {
            mock3(EMPTY, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.loopReportState()).isEqualTo(ReportState.NONE);
            assertThat(res.tenDayAvgPercent()).isNull();
            assertThat(res.reportStateMessage()).contains("jun");
        }
    }

    // ===================== 달성률 계산 =====================

    @Nested
    @DisplayName("달성률 계산 (calcLoopAchievePercent)")
    class AchievePercentCalc {

        @Test
        @DisplayName("체크리스트 5개 중 3개 완료 → 달성률 60%")
        void checklist_3of5_gives60() {
            LoopRule r = rule(1L);
            stubMapper();
            mock3(EMPTY, List.of(partial(1L, "L", TODAY.minusDays(1), r, 5, 3)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekAvgPercent()).isEqualTo(60L);
        }

        @Test
        @DisplayName("체크리스트 없음 + completed=true → 달성률 100%")
        void noChecklist_completed_gives100() {
            LoopRule r = rule(1L);
            stubMapper();
            mock3(EMPTY, List.of(done(1L, "L", TODAY.minusDays(1), r)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekAvgPercent()).isEqualTo(100L);
        }

        @Test
        @DisplayName("체크리스트 없음 + completed=false → 달성률 0%")
        void noChecklist_notCompleted_gives0() {
            LoopRule r = rule(1L);
            mock3(EMPTY, List.of(undone(1L, "L", TODAY.minusDays(1), r)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekAvgPercent()).isEqualTo(0L);
        }

        @Test
        @DisplayName("체크리스트 5개 중 0개 완료 → 달성률 0%")
        void checklist_0of5_gives0() {
            LoopRule r = rule(1L);
            mock3(EMPTY, List.of(partial(1L, "L", TODAY.minusDays(1), r, 5, 0)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekAvgPercent()).isEqualTo(0L);
        }
    }

    // ===================== 날짜별 일일 달성률 평균 =====================

    @Nested
    @DisplayName("날짜별 일일 달성률 평균 (tenDayAvgPercent, weekAvgPercent)")
    class DailyAverageCalc {

        @Test
        @DisplayName("10일: 같은 날 루프 여러 개 → 날짜별 평균의 평균으로 계산")
        void tenDay_sameDateMultipleLoops_dateBasedAvg() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            // Day1: 100% + 0% → 일일 평균 50%
            // Day2: 100%      → 일일 평균 100%
            // 전체: (50 + 100) / 2 = 75%
            List<Loop> tenDay = List.of(
                    done(1L, "A", TODAY.minusDays(1), rA),
                    undone(2L, "B", TODAY.minusDays(1), rB),
                    done(3L, "A", TODAY.minusDays(2), rA));
            mock3(tenDay, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.tenDayAvgPercent()).isEqualTo(75L);
        }

        @Test
        @DisplayName("주간: 같은 날 루프 여러 개 → 날짜별 평균의 평균으로 계산")
        void week_sameDateMultipleLoops_dateBasedAvg() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // Day1: 100% + 0% → 일일 평균 50%
            // Day2: 100%      → 일일 평균 100%
            // 전체: (50 + 100) / 2 = 75%
            List<Loop> sevenDay = List.of(
                    done(1L, "A", TODAY.minusDays(1), rA),
                    undone(2L, "B", TODAY.minusDays(1), rB),
                    done(3L, "A", TODAY.minusDays(2), rA));
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekAvgPercent()).isEqualTo(75L);
        }

        @Test
        @DisplayName("인스턴스 평균과 날짜별 평균이 다른 케이스 검증")
        void dateBasedAvg_differsFromInstanceAvg() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            LoopRule rC = rule(3L);
            // Day1: 100% + 100% + 0% → 일일 평균 66.67
            // Day2: 100%             → 일일 평균 100.0
            // 날짜별 평균: (66.67 + 100) / 2 = 83.33 → round = 83% → GOOD
            // 인스턴스 평균이었다면: (100+100+0+100) / 4 = 75% → OK
            List<Loop> tenDay = List.of(
                    done(1L, "A", TODAY.minusDays(1), rA),
                    done(2L, "B", TODAY.minusDays(1), rB),
                    undone(3L, "C", TODAY.minusDays(1), rC),
                    done(4L, "A", TODAY.minusDays(2), rA));
            mock3(tenDay, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.tenDayAvgPercent()).isEqualTo(83L);
            assertThat(res.loopReportState()).isEqualTo(ReportState.GOOD);
        }
    }

    // ===================== sevenDayDoneCount 경계값 =====================

    @Nested
    @DisplayName("sevenDayDoneCount 경계값 (50% 기준)")
    class SevenDayDoneCountBoundary {

        @Test
        @DisplayName("달성률 50% → 완료(done)로 카운트")
        void percent50_counted() {
            LoopRule r = rule(1L);
            // 2 checklists, 1 done → round(50.0) = 50%
            mock3(EMPTY, List.of(partial(1L, "L", TODAY.minusDays(1), r, 2, 1)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.sevenDayTotalCount()).isEqualTo(1L);
            assertThat(res.sevenDayDoneCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("달성률 40% → 미완료로 카운트")
        void percent40_notCounted() {
            LoopRule r = rule(1L);
            // 5 checklists, 2 done → round(40.0) = 40%
            mock3(EMPTY, List.of(partial(1L, "L", TODAY.minusDays(1), r, 5, 2)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.sevenDayTotalCount()).isEqualTo(1L);
            assertThat(res.sevenDayDoneCount()).isEqualTo(0L);
        }
    }

    // ===================== 주간 잘한 루프 선정 =====================

    @Nested
    @DisplayName("주간 잘한 루프 선정 (pickGoodProgressLoopDto)")
    class WeekGoodLoopSelection {

        @Test
        @DisplayName("평균 달성률 70% → 잘한 루프 후보 포함 (경계)")
        void threshold70_included() {
            LoopRule r = rule(1L);
            stubMapper();
            // 10 checklists, 7 done → round(70.0) = 70%
            mock3(EMPTY, List.of(partial(1L, "Running", TODAY.minusDays(1), r, 10, 7)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Running");
            assertThat(res.weekReportDto().goodProgressLoopDto().loopAchievePercent()).isEqualTo(70L);
        }

        @Test
        @DisplayName("평균 달성률 60% → 잘한 루프 후보 제외")
        void threshold60_excluded() {
            LoopRule r = rule(1L);
            // 5 checklists, 3 done → 60% < 70%
            mock3(EMPTY, List.of(partial(1L, "Running", TODAY.minusDays(1), r, 5, 3)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isNull();
        }

        @Test
        @DisplayName("1순위: 평균 달성률이 더 높은 반복루프 선정")
        void tieBreaker1_higherAvgWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: 100%, rB: 80%
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", TODAY.minusDays(1), rA),
                    partial(2L, "Study", TODAY.minusDays(1), rB, 5, 4));
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Running");
            assertThat(res.weekReportDto().goodProgressLoopDto().loopAchievePercent()).isEqualTo(100L);
        }

        @Test
        @DisplayName("2순위: 달성률 동률 시 100% 달성 일수가 더 많은 반복루프 선정")
        void tieBreaker2_more100DaysWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: [100, 100, 40] avg=80, fullDays=2
            // rB: [100, 100, 100, 20] avg=80, fullDays=3 → rB 승
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", TODAY.minusDays(1), rA),
                    done(2L, "Running", TODAY.minusDays(2), rA),
                    partial(3L, "Running", TODAY.minusDays(3), rA, 5, 2),     // 40%
                    done(4L, "Study", TODAY.minusDays(1), rB),
                    done(5L, "Study", TODAY.minusDays(2), rB),
                    done(6L, "Study", TODAY.minusDays(3), rB),
                    partial(7L, "Study", TODAY.minusDays(4), rB, 5, 1));      // 20%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Study");
            assertThat(res.weekReportDto().goodProgressLoopDto().loopAchievePercent()).isEqualTo(80L);
        }

        @Test
        @DisplayName("3순위: 최근 실행일이 더 가까운 반복루프 선정")
        void tieBreaker3_recentExecutionWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: [100, 60] avg=80, fullDays=1, lastExec=today-4
            // rB: [100, 60] avg=80, fullDays=1, lastExec=today-2 → rB 승
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", TODAY.minusDays(5), rA),
                    partial(2L, "Running", TODAY.minusDays(4), rA, 5, 3),     // 60%
                    done(3L, "Study", TODAY.minusDays(3), rB),
                    partial(4L, "Study", TODAY.minusDays(2), rB, 5, 3));      // 60%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Study");
        }

        @Test
        @DisplayName("4순위: 생성일이 더 오래된 반복루프 선정")
        void tieBreaker4_olderCreationWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: [100, 60] avg=80, fullDays=1, lastExec=today-2, created=OLDER → rA 승
            // rB: [100, 60] avg=80, fullDays=1, lastExec=today-2, created=NEWER
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", TODAY.minusDays(3), rA, OLDER),
                    partial(2L, "Running", TODAY.minusDays(2), rA, 5, 3, OLDER),  // 60%
                    done(3L, "Study", TODAY.minusDays(3), rB, NEWER),
                    partial(4L, "Study", TODAY.minusDays(2), rB, 5, 3, NEWER));   // 60%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Running");
        }
    }

    // ===================== 주간 버거운 루프 선정 =====================

    @Nested
    @DisplayName("주간 버거운 루프 선정 (pickBadProgressLoopDto)")
    class WeekBadLoopSelection {

        @Test
        @DisplayName("평균 달성률 40% → 버거운 루프 후보 포함")
        void threshold40_included() {
            LoopRule r = rule(1L);
            stubMapper();
            // 5 checklists, 2 done → 40% < 50%
            mock3(EMPTY, List.of(partial(1L, "Running", TODAY.minusDays(1), r, 5, 2)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Running");
            assertThat(res.weekReportDto().badProgressLoopDto().loopAchievePercent()).isEqualTo(40L);
        }

        @Test
        @DisplayName("평균 달성률 50% → 버거운 루프 후보 제외 (경계)")
        void threshold50_excluded() {
            LoopRule r = rule(1L);
            // 2 checklists, 1 done → 50% ≥ 50% → 제외
            mock3(EMPTY, List.of(partial(1L, "Running", TODAY.minusDays(1), r, 2, 1)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isNull();
        }

        @Test
        @DisplayName("1순위: 평균 달성률이 더 낮은 반복루프 선정")
        void tieBreaker1_lowerAvgWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: 20%, rB: 40% → rA(더 낮음) 승
            List<Loop> sevenDay = List.of(
                    partial(1L, "Running", TODAY.minusDays(1), rA, 5, 1),    // 20%
                    partial(2L, "Study", TODAY.minusDays(1), rB, 5, 2));     // 40%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Running");
            assertThat(res.weekReportDto().badProgressLoopDto().loopAchievePercent()).isEqualTo(20L);
        }

        @Test
        @DisplayName("2순위: 달성률 동률 시 0% 달성 일수가 더 많은 반복루프 선정")
        void tieBreaker2_more0DaysWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: [0, 0, 60] avg=20, zeroDays=2 → rA 승
            // rB: [0, 40, 20] avg=20, zeroDays=1
            List<Loop> sevenDay = List.of(
                    undone(1L, "Running", TODAY.minusDays(1), rA),             // 0%
                    undone(2L, "Running", TODAY.minusDays(2), rA),             // 0%
                    partial(3L, "Running", TODAY.minusDays(3), rA, 5, 3),      // 60%
                    undone(4L, "Study", TODAY.minusDays(1), rB),               // 0%
                    partial(5L, "Study", TODAY.minusDays(2), rB, 5, 2),        // 40%
                    partial(6L, "Study", TODAY.minusDays(3), rB, 5, 1));       // 20%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Running");
        }

        @Test
        @DisplayName("3순위: 최근 실행일이 더 오래된 반복루프 선정")
        void tieBreaker3_olderExecutionWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: [0, 40] avg=20, zeroDays=1, lastExec=today-5 → rA 승 (더 오래됨)
            // rB: [0, 40] avg=20, zeroDays=1, lastExec=today-2
            List<Loop> sevenDay = List.of(
                    undone(1L, "Running", TODAY.minusDays(6), rA),
                    partial(2L, "Running", TODAY.minusDays(5), rA, 5, 2),     // 40%
                    undone(3L, "Study", TODAY.minusDays(3), rB),
                    partial(4L, "Study", TODAY.minusDays(2), rB, 5, 2));      // 40%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Running");
        }

        @Test
        @DisplayName("4순위: 생성일이 더 오래된 반복루프 선정")
        void tieBreaker4_olderCreationWins() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            // rA: [0, 40] avg=20, zeroDays=1, lastExec=today-2, created=OLDER → rA 승
            // rB: [0, 40] avg=20, zeroDays=1, lastExec=today-2, created=NEWER
            List<Loop> sevenDay = List.of(
                    undone(1L, "Running", TODAY.minusDays(3), rA, OLDER),
                    partial(2L, "Running", TODAY.minusDays(2), rA, 5, 2, OLDER),   // 40%
                    undone(3L, "Study", TODAY.minusDays(3), rB, NEWER),
                    partial(4L, "Study", TODAY.minusDays(2), rB, 5, 2, NEWER));    // 40%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Running");
        }
    }

    // ===================== 주간 DetailReportState 상태 조합 + Fallback 메시지 =====================

    @Nested
    @DisplayName("주간 DetailReportState 상태 조합 및 Fallback 메시지")
    class WeekDetailReportState {

        @Test
        @DisplayName("BOTH_EXIST: 잘한 루프(≥70%)와 버거운 루프(<50%) 모두 존재")
        void bothExist() {
            LoopRule rGood = rule(1L);
            LoopRule rBad = rule(2L);
            stubMapper();
            List<Loop> sevenDay = List.of(
                    done(1L, "Good", TODAY.minusDays(1), rGood),        // 100% → 잘한 루프
                    undone(2L, "Bad", TODAY.minusDays(1), rBad));       // 0% → 버거운 루프
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().detailReportState()).isEqualTo(DetailReportState.BOTH_EXIST);
            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Good");
            assertThat(res.weekReportDto().goodProgressLoopDto().message()).isNull();
            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Bad");
            assertThat(res.weekReportDto().badProgressLoopDto().message()).isNull();
        }

        @Test
        @DisplayName("ONLY_GOOD: 잘한 루프만 존재 → 버거운 쪽 Fallback 메시지")
        void onlyGood() {
            LoopRule r = rule(1L);
            stubMapper();
            // 100% → good. 50% 미만 없음 → bad null.
            mock3(EMPTY, List.of(done(1L, "Good", TODAY.minusDays(1), r)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().detailReportState()).isEqualTo(DetailReportState.ONLY_GOOD);
            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Good");
            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isNull();
            assertThat(res.weekReportDto().badProgressLoopDto().message())
                    .isEqualTo(WeekReportMessages.ONLY_GOOD);
        }

        @Test
        @DisplayName("ONLY_BAD: 버거운 루프만 존재 → 잘한 쪽 Fallback 메시지")
        void onlyBad() {
            LoopRule rMid = rule(1L);
            LoopRule rBad = rule(2L);
            stubMapper();
            // rMid: 60% → not good(< 70), not bad(≥ 50)
            // rBad: 20% → bad(< 50)
            List<Loop> sevenDay = List.of(
                    partial(1L, "Mid", TODAY.minusDays(1), rMid, 5, 3),     // 60%
                    partial(2L, "Bad", TODAY.minusDays(1), rBad, 5, 1));    // 20%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().detailReportState()).isEqualTo(DetailReportState.ONLY_BAD);
            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isNull();
            assertThat(res.weekReportDto().goodProgressLoopDto().message())
                    .isEqualTo(WeekReportMessages.ONLY_BAD);
            assertThat(res.weekReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Bad");
        }

        @Test
        @DisplayName("NONE: 반복 루프 없음 → 양쪽 모두 Fallback 메시지")
        void none() {
            mock3(EMPTY, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().detailReportState()).isEqualTo(DetailReportState.NONE);
            assertThat(res.weekReportDto().goodProgressLoopDto().message())
                    .isEqualTo(WeekReportMessages.NONE);
            assertThat(res.weekReportDto().badProgressLoopDto().message())
                    .isEqualTo(WeekReportMessages.NONE);
        }

        @Test
        @DisplayName("조건 A: 반복 루프 있지만 모두 50~69% 구간 → 개별 fallback 메시지")
        void conditionA_loopsExistButNoneQualify() {
            LoopRule r = rule(1L);
            // 60%: not good(< 70), not bad(≥ 50) → 둘 다 선정 불가
            mock3(EMPTY, List.of(partial(1L, "Mid", TODAY.minusDays(1), r, 5, 3)), EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().detailReportState()).isEqualTo(DetailReportState.NONE);
            // 조건 A: 루프는 있지만 조건 미충족 → 개별 fallback 메시지
            assertThat(res.weekReportDto().goodProgressLoopDto().message())
                    .isEqualTo(WeekReportMessages.ONLY_BAD);
            assertThat(res.weekReportDto().badProgressLoopDto().message())
                    .isEqualTo(WeekReportMessages.ONLY_GOOD);
        }
    }

    // ===================== weekCard 날짜별 평균 계산 =====================

    @Nested
    @DisplayName("weekCard 날짜별 평균 달성률 계산")
    class WeekCardCalculation {

        @Test
        @DisplayName("같은 날짜에 여러 루프 → 날짜별 평균 달성률로 계산")
        void sameDateMultipleLoops_averaged() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            LocalDate day1 = TODAY.minusDays(1);
            // day1: "Running" 100% + "Study" 60% → 평균 80%
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", day1, rA),                         // 100%
                    partial(2L, "Study", day1, rB, 5, 3));                 // 60%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekCard()).containsEntry(day1, 80L);
        }

        @Test
        @DisplayName("날짜별 루프가 1개씩 → 개별 달성률 그대로 표시")
        void differentDates_individualPercent() {
            LoopRule r = rule(1L);
            stubMapper();
            LocalDate day1 = TODAY.minusDays(1);
            LocalDate day2 = TODAY.minusDays(2);
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", day1, r),                          // 100%
                    partial(2L, "Running", day2, r, 5, 3));                // 60%
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.weekReportDto().weekCard())
                    .containsEntry(day1, 100L)
                    .containsEntry(day2, 60L);
        }
    }

    // ===================== monthCard 날짜별 평균 계산 =====================

    @Nested
    @DisplayName("monthCard 날짜별 평균 달성률 계산")
    class MonthCardCalculation {

        @Test
        @DisplayName("같은 날짜에 여러 반복루프 → 날짜별 평균 달성률로 계산")
        void sameDateMultipleRules_averaged() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            LoopRule rC = rule(3L);
            stubMapper();
            LocalDate day1 = TODAY.withDayOfMonth(1);
            // day1: rA 100% + rB 60% + rC 0% → 평균 53%
            List<Loop> month = List.of(
                    done(1L, "Running", day1, rA),                         // 100%
                    partial(2L, "Study", day1, rB, 5, 3),                  // 60%
                    undone(3L, "Read", day1, rC));                         // 0%
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().monthCard()).containsEntry(day1, 53L);
        }

        @Test
        @DisplayName("여러 날짜에 걸친 루프 → 날짜별 독립 계산")
        void multipleDates_independentCalculation() {
            LoopRule rA = rule(1L);
            LoopRule rB = rule(2L);
            stubMapper();
            LocalDate day1 = TODAY.withDayOfMonth(1);
            LocalDate day5 = TODAY.withDayOfMonth(5);
            LocalDate day10 = TODAY.withDayOfMonth(10);
            List<Loop> month = List.of(
                    done(1L, "Running", day1, rA),                         // day1: 100%
                    done(2L, "Study", day1, rB),                           // day1: 100% → 평균 100%
                    partial(3L, "Running", day5, rA, 5, 2),                // day5: 40%
                    undone(4L, "Study", day5, rB),                         // day5: 0% → 평균 20%
                    done(5L, "Running", day10, rA),                        // day10: 100%
                    partial(6L, "Study", day10, rB, 5, 3));                // day10: 60% → 평균 80%
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().monthCard())
                    .containsEntry(day1, 100L)
                    .containsEntry(day5, 20L)
                    .containsEntry(day10, 80L);
        }

        @Test
        @DisplayName("루프 없으면 빈 Map 반환")
        void noLoops_emptyMap() {
            mock3(EMPTY, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().monthCard()).isEmpty();
        }
    }

    // ===================== 월간 최소 스케줄 일수 필터 =====================

    @Nested
    @DisplayName("월간 최소 스케줄 일수 필터 (3일 미만 제외)")
    class MonthMinScheduledDaysFilter {

        @Test
        @DisplayName("반복루프 스케줄 2일 → 잘한/버거운 루프 평가에서 제외")
        void rule2days_excluded() {
            LoopRule r = rule(1L);
            // 2 distinct dates → filtered out
            List<Loop> month = List.of(
                    done(1L, "Running", TODAY.withDayOfMonth(1), r),
                    done(2L, "Running", TODAY.withDayOfMonth(2), r));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.NONE);
            assertThat(res.monthReportDto().goodProgressLoopDto().loopTitle()).isNull();
            assertThat(res.monthReportDto().badProgressLoopDto().loopTitle()).isNull();
        }

        @Test
        @DisplayName("반복루프 스케줄 정확히 3일 → 잘한/버거운 루프 평가에 포함 (경계)")
        void rule3days_included() {
            LoopRule r = rule(1L);
            stubMapper();
            // 3 distinct dates → included, all done → avg 100% → good
            List<Loop> month = List.of(
                    done(1L, "Running", TODAY.withDayOfMonth(1), r),
                    done(2L, "Running", TODAY.withDayOfMonth(2), r),
                    done(3L, "Running", TODAY.withDayOfMonth(3), r));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Running");
            assertThat(res.monthReportDto().goodProgressLoopDto().loopAchievePercent()).isEqualTo(100L);
        }

        @Test
        @DisplayName("2일 반복루프는 제외, 3일 반복루프만 평가에 포함")
        void mixedRules_onlyEligibleEvaluated() {
            LoopRule r2days = rule(1L);
            LoopRule r3days = rule(2L);
            stubMapper();
            // r2days: 2 dates, avg 100% → filtered out (< 3 days)
            // r3days: 3 dates, avg 100% → included → good
            List<Loop> month = List.of(
                    done(1L, "Short", TODAY.withDayOfMonth(1), r2days),
                    done(2L, "Short", TODAY.withDayOfMonth(2), r2days),
                    done(3L, "Long", TODAY.withDayOfMonth(1), r3days),
                    done(4L, "Long", TODAY.withDayOfMonth(2), r3days),
                    done(5L, "Long", TODAY.withDayOfMonth(3), r3days));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            // r2days(Short)는 제외, r3days(Long)만 평가 → Long 선정
            assertThat(res.monthReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Long");
        }

        @Test
        @DisplayName("같은 날짜에 루프 3개지만 distinct 일수 2일 → 평가에서 제외")
        void duplicateDates_distinctCountUsed() {
            LoopRule r = rule(1L);
            // 3 loops on 2 distinct dates → distinct = 2 < 3 → filtered out
            List<Loop> month = List.of(
                    done(1L, "Running", TODAY.withDayOfMonth(1), r),
                    done(2L, "Running", TODAY.withDayOfMonth(1), r),   // 같은 날짜 중복
                    done(3L, "Running", TODAY.withDayOfMonth(2), r));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.NONE);
            assertThat(res.monthReportDto().goodProgressLoopDto().loopTitle()).isNull();
        }
    }

    // ===================== 월간 Fallback 메시지 =====================

    @Nested
    @DisplayName("월간 DetailReportState 및 Fallback 메시지")
    class MonthDetailReportState {

        @Test
        @DisplayName("ONLY_GOOD: 월간 잘한 루프만 존재 → 월간 전용 버거운 Fallback 메시지")
        void monthOnlyGood_fallbackMessage() {
            LoopRule r = rule(1L);
            stubMapper();
            List<Loop> month = List.of(
                    done(1L, "G", TODAY.withDayOfMonth(1), r),
                    done(2L, "G", TODAY.withDayOfMonth(2), r),
                    done(3L, "G", TODAY.withDayOfMonth(3), r));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.ONLY_GOOD);
            assertThat(res.monthReportDto().badProgressLoopDto().message())
                    .isEqualTo(MonthReportMessages.ONLY_GOOD);
        }

        @Test
        @DisplayName("ONLY_BAD: 월간 버거운 루프만 존재 → 월간 전용 잘한 Fallback 메시지")
        void monthOnlyBad_fallbackMessage() {
            LoopRule rMid = rule(1L);
            LoopRule rBad = rule(2L);
            stubMapper();
            // rMid: 60% × 3일 → not good(< 70), not bad(≥ 50)
            // rBad: 20% × 3일 → bad(< 50)
            List<Loop> month = List.of(
                    partial(1L, "Mid", TODAY.withDayOfMonth(1), rMid, 5, 3),
                    partial(2L, "Mid", TODAY.withDayOfMonth(2), rMid, 5, 3),
                    partial(3L, "Mid", TODAY.withDayOfMonth(3), rMid, 5, 3),
                    partial(4L, "Bad", TODAY.withDayOfMonth(1), rBad, 5, 1),
                    partial(5L, "Bad", TODAY.withDayOfMonth(2), rBad, 5, 1),
                    partial(6L, "Bad", TODAY.withDayOfMonth(3), rBad, 5, 1));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.ONLY_BAD);
            assertThat(res.monthReportDto().goodProgressLoopDto().message())
                    .isEqualTo(MonthReportMessages.ONLY_BAD);
        }

        @Test
        @DisplayName("NONE: 월간 반복 루프 없음 → 양쪽 모두 월간 전용 Fallback 메시지")
        void monthNone_fallbackMessage() {
            mock3(EMPTY, EMPTY, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.NONE);
            assertThat(res.monthReportDto().goodProgressLoopDto().message())
                    .isEqualTo(MonthReportMessages.NONE);
            assertThat(res.monthReportDto().badProgressLoopDto().message())
                    .isEqualTo(MonthReportMessages.NONE);
        }

        @Test
        @DisplayName("주간은 min days 필터 미적용 → 2일짜리 룰도 평가 대상")
        void weekNoMinDaysFilter_2daysEvaluated() {
            LoopRule r = rule(1L);
            stubMapper();
            // 2 distinct dates → 월간이면 제외되지만, 주간에서는 필터 없이 평가
            List<Loop> sevenDay = List.of(
                    done(1L, "Running", TODAY.minusDays(1), r),
                    done(2L, "Running", TODAY.minusDays(2), r));
            mock3(EMPTY, sevenDay, EMPTY);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            // 주간: 필터 없으므로 2일짜리 룰도 잘한 루프로 선정
            assertThat(res.weekReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Running");
            assertThat(res.weekReportDto().goodProgressLoopDto().loopAchievePercent()).isEqualTo(100L);
        }

        @Test
        @DisplayName("조건 A: 월간 평가 대상 루프 있지만 모두 50~69% 구간 → 개별 fallback 메시지")
        void monthConditionA_eligibleLoopsExistButNoneQualify() {
            LoopRule r = rule(1L);
            // 60% × 3일 → 필터 통과, not good(< 70), not bad(≥ 50) → 둘 다 선정 불가
            List<Loop> month = List.of(
                    partial(1L, "Mid", TODAY.withDayOfMonth(1), r, 5, 3),
                    partial(2L, "Mid", TODAY.withDayOfMonth(2), r, 5, 3),
                    partial(3L, "Mid", TODAY.withDayOfMonth(3), r, 5, 3));
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.NONE);
            // 조건 A: 평가 대상 루프는 있지만 조건 미충족 → 개별 fallback 메시지
            assertThat(res.monthReportDto().goodProgressLoopDto().message())
                    .isEqualTo(MonthReportMessages.ONLY_BAD);
            assertThat(res.monthReportDto().badProgressLoopDto().message())
                    .isEqualTo(MonthReportMessages.ONLY_GOOD);
        }

        @Test
        @DisplayName("BOTH_EXIST: 월간 잘한 루프 + 버거운 루프 모두 존재 (3일 이상)")
        void monthBothExist() {
            LoopRule rGood = rule(1L);
            LoopRule rBad = rule(2L);
            stubMapper();
            List<Loop> month = List.of(
                    done(1L, "Good", TODAY.withDayOfMonth(1), rGood),
                    done(2L, "Good", TODAY.withDayOfMonth(2), rGood),
                    done(3L, "Good", TODAY.withDayOfMonth(3), rGood),           // avg 100%
                    undone(4L, "Bad", TODAY.withDayOfMonth(1), rBad),
                    undone(5L, "Bad", TODAY.withDayOfMonth(2), rBad),
                    undone(6L, "Bad", TODAY.withDayOfMonth(3), rBad));           // avg 0%
            mock3(EMPTY, EMPTY, month);

            LoopReportResponse res = loopReportService.getLoopReport(user());

            assertThat(res.monthReportDto().detailReportState()).isEqualTo(DetailReportState.BOTH_EXIST);
            assertThat(res.monthReportDto().goodProgressLoopDto().loopTitle()).isEqualTo("Good");
            assertThat(res.monthReportDto().badProgressLoopDto().loopTitle()).isEqualTo("Bad");
        }
    }
}
