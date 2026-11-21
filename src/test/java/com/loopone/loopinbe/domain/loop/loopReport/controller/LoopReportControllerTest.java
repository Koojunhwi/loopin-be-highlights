package com.loopone.loopinbe.domain.loop.loopReport.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.loop.loopReport.dto.MonthReportDto;
import com.loopone.loopinbe.domain.loop.loopReport.dto.ProgressLoopDto;
import com.loopone.loopinbe.domain.loop.loopReport.dto.WeekReportDto;
import com.loopone.loopinbe.domain.loop.loopReport.dto.res.LoopReportResponse;
import com.loopone.loopinbe.domain.loop.loopReport.enums.DetailReportState;
import com.loopone.loopinbe.domain.loop.loopReport.enums.ReportState;
import com.loopone.loopinbe.domain.loop.loopReport.service.LoopReportService;
import com.loopone.loopinbe.global.config.SecurityConfig;
import com.loopone.loopinbe.global.config.WebConfig;
import com.loopone.loopinbe.global.security.JwtAuthenticationFilter;
import com.loopone.loopinbe.global.alert.RealTimeAlertSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = LoopReportController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        JwtAuthenticationFilter.class,
                        SecurityConfig.class,
                        WebConfig.class
                })
        },
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                OAuth2ClientAutoConfiguration.class,
                OAuth2ClientWebSecurityAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class LoopReportControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    // ===== controller deps =====
    @MockitoBean RealTimeAlertSender realTimeAlertSender;
    @MockitoBean LoopReportService loopReportService;

    private MemberPrincipal currentUser;

    @BeforeEach
    void setUp() {
        currentUser = new MemberPrincipal(
                1L, "jun@loop.in", null, "jun", "010-0000-0000",
                Member.Gender.MALE, LocalDate.of(2000, 1, 1),
                null, Member.State.NORMAL, Member.MemberRole.ROLE_USER,
                Member.OAuthProvider.GOOGLE, "provider-id"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.getAuthorities())
        );
    }

    @TestConfiguration
    static class WebMvcTestConfig implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver());
        }
    }

    @Test
    @DisplayName("GET /rest-api/v1/report -> 200 OK")
    void getLoopReport_success() throws Exception {
        // given
        var weekReport = new WeekReportDto(
                DetailReportState.NONE, null, new TreeMap<>(),
                new ProgressLoopDto(null, null, null, "아직 평가할 수 있는 반복 루프가 없어요."),
                new ProgressLoopDto(null, null, null, "아직 평가할 수 있는 반복 루프가 없어요.")
        );
        var monthReport = new MonthReportDto(
                DetailReportState.NONE, new TreeMap<>(),
                new ProgressLoopDto(null, null, null, "아직 평가할 수 있는 반복 루프가 없어요."),
                new ProgressLoopDto(null, null, null, "아직 평가할 수 있는 반복 루프가 없어요.")
        );
        var response = new LoopReportResponse(
                ReportState.NONE,
                "리포트 메시지",
                0L, 0L, null,
                weekReport, monthReport
        );
        given(loopReportService.getLoopReport(any())).willReturn(response);

        // when & then
        mvc.perform(get("/rest-api/v1/report"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.loopReportState").value("NONE"))
                .andExpect(jsonPath("$.data.reportStateMessage").value("리포트 메시지"))
                .andExpect(jsonPath("$.data.sevenDayDoneCount").value(0))
                .andExpect(jsonPath("$.data.sevenDayTotalCount").value(0));
        then(loopReportService).should().getLoopReport(any());
    }
}
