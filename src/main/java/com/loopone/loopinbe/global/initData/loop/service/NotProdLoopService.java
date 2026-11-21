package com.loopone.loopinbe.global.initData.loop.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.mapper.MemberMapper;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.domain.loop.loop.dto.req.LoopCreateRequest;
import com.loopone.loopinbe.domain.loop.loop.enums.RepeatType;
import com.loopone.loopinbe.domain.loop.loop.service.LoopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotProdLoopService {
    private final LoopService loopService;
    private final MemberRepository memberRepository;
    private final MemberMapper memberMapper;
    private final EncryptionProperties encryptionProperties;
    private static final String USER1_EMAIL = "user1@example.com";
    private static final String USER_EMAIL_FORMAT = "user%d@example.com";
    private static final String LOOP_TRAVEL = "강릉 당일치기";
    private static final String LOOP_RUNNING = "동계 런닝 훈련";
    private static final String LOOP_TOEIC = "토익 공부하기";
    private static final String LOOP_CODING = "코딩 테스트 준비";

    // [유저 1이 주간 루프 생성]
    // 0) 단일 루프(오늘) - 강릉 당일치기
    // 1) 반복 루프(주 2회: 월/수, 이번주/저번주) - 동계 런닝 훈련
    // 2) 반복 루프(주 2회: 월/수, 이번주/저번주) - 토익 공부하기
    @Transactional
    public void createWeekLoops() {
        MemberPrincipal user1 = user1CurrentUser();
        LocalDate today = LocalDate.now();
        LocalDate thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekMonday = thisWeekMonday.minusWeeks(1);
        LocalDate thisWeekSunday = thisWeekMonday.plusDays(6);
        // 0) 단일 루프: 강릉 당일치기
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_TRAVEL,
                        "친구들이랑 여행",
                        RepeatType.NONE,
                        today,
                        null,
                        null,
                        null,
                        List.of("주문진 해수욕장"),
                        null
                ),
                user1
        );
        // 1) 반복 루프: 동계 런닝 훈련 (월/수) - 이번주/저번주
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_RUNNING,
                        "3km 10분 달성 목표",
                        RepeatType.WEEKLY,
                        null,
                        List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                        lastWeekMonday,
                        thisWeekSunday,
                        List.of("아침에 3km 런닝", "런닝 후 샐러드 건강식 먹기"),
                        null
                ),
                user1
        );
        // 2) 반복 루프: 토익 공부하기 (월/수) - 이번주/저번주
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_TOEIC,
                        "950점 목표",
                        RepeatType.WEEKLY,
                        null,
                        List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                        lastWeekMonday,
                        thisWeekSunday,
                        List.of("아침에 오답단어 복습", "듣기 연습", "기출 1회 풀기", "오답노트하기"),
                        null
                ),
                user1
        );
        log.info("[NotProd] createWeekLoops done for user1={}", USER1_EMAIL);
    }

    // [유저 N이 월간 루프 생성]
    // 0) 단일 루프(당일)
    // 1) 반복 루프(주 2회: 월/수, 이번달 내내) - 동계 런닝 훈련
    // 2) 반복 루프(주 2회: 월/수, 이번달 내내) - 토익 공부하기
    // 3) 반복 루프(주 1회: 화, 이번달 내내) - 코딩 테스트 준비
    public void createMonthLoops(int fromUserNo, int toUserNo) {
        if (fromUserNo <= 0 || toUserNo <= 0) {
            throw new IllegalArgumentException("userNo must be positive. from=" + fromUserNo + ", to=" + toUserNo);
        }
        if (fromUserNo > toUserNo) {
            throw new IllegalArgumentException("fromUserNo must be <= toUserNo. from=" + fromUserNo + ", to=" + toUserNo);
        }
        int success = 0;
        int fail = 0;

        for (int userNo = fromUserNo; userNo <= toUserNo; userNo++) {
            try {
                createMonthLoopsForUser(userNo); // 유저 단위 트랜잭션
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("[NotProd] createMonthLoops failed for userNo={} (email={}). reason={}",
                        userNo, userEmail(userNo), e.getMessage(), e);
            }
        }
        log.info("[NotProd] createMonthLoops range done. from={}, to={}, success={}, fail={}",
                fromUserNo, toUserNo, success, fail);
    }

    // 유저 한 명 단위로 트랜잭션 분리 (배치 안정성)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createMonthLoopsForUser(int userNo) {
        MemberPrincipal user = userCurrentUser(userNo);
        LocalDate today = LocalDate.now();
        LocalDate thisMonthStart = today.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate thisMonthEnd = today.with(TemporalAdjusters.lastDayOfMonth());
        // 이번주 월요일
        LocalDate thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // 저번주 월요일
        LocalDate lastWeekMonday = thisWeekMonday.minusWeeks(1);
        // 이번주 일요일
        LocalDate thisWeekSunday = thisWeekMonday.plusDays(6);
        // 0) 단일 루프: 당일 치기 여행
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_TRAVEL,
                        "친구들이랑 여행",
                        RepeatType.NONE,
                        today,
                        null,
                        null,
                        null,
                        List.of("주문진 해수욕장"),
                        null
                ),
                user
        );
        // 1) 반복 루프: 동계 런닝 훈련 (월/수) - 이번달 내내
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_RUNNING,
                        "3km 10분 달성 목표",
                        RepeatType.WEEKLY,
                        null,
                        List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                        thisMonthStart,
                        thisMonthEnd,
                        List.of("런닝 전 스트레칭", "아침에 3km 런닝", "런닝 후 1km 조깅", "마무리 스트레칭", "런닝 후 샐러드 건강식 먹기"),
                        null
                ),
                user
        );
        // 2) 반복 루프: 토익 공부하기 (월/수) - 이번달 내내
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_TOEIC,
                        "950점 목표",
                        RepeatType.WEEKLY,
                        null,
                        List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                        thisMonthStart,
                        thisMonthEnd,
                        List.of("아침에 오답단어 복습", "듣기 연습", "기출 1회 풀기", "오답노트하기", "오답 단어 1회독"),
                        null
                ),
                user
        );
        // 3) 반복 루프: 코딩 테스트 준비 (화)
        loopService.createLoop(
                new LoopCreateRequest(
                        LOOP_CODING,
                        "1일 1회 기출 풀기",
                        RepeatType.WEEKLY,
                        null,
                        List.of(DayOfWeek.TUESDAY),
                        lastWeekMonday,
                        thisWeekSunday,
                        List.of("카카오 기출 1회 풀기"),
                        null
                ),
                user
        );
        log.info("[NotProd] createMonthLoops done for userNo={} email={}", userNo, userEmail(userNo));
    }

    // ----------------- 헬퍼 메서드 -----------------

    private String userEmail(int userNo) {
        return USER_EMAIL_FORMAT.formatted(userNo);
    }

    private MemberPrincipal userCurrentUser(int userNo) {
        String email = userEmail(userNo);
        Member user = memberRepository.findByEmailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .orElseThrow(() -> new IllegalStateException("user not found: " + email));
        return MemberPrincipal.from(user);
    }

    private MemberPrincipal user1CurrentUser() {
        Member user1 = memberRepository.findByEmailBlindIndex(encryptionProperties.generateBlindIndex(USER1_EMAIL))
                .orElseThrow(() -> new IllegalStateException("user1 not found: " + USER1_EMAIL));
        return MemberPrincipal.from(user1);
    }
}
