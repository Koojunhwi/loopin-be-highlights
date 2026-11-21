package com.loopone.loopinbe.domain.notification.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.notification.notification.dto.req.NotificationRequest;
import com.loopone.loopinbe.domain.notification.notification.dto.res.NotificationResponse;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.notification.notification.service.NotificationService;
import com.loopone.loopinbe.global.common.response.PageResponse;
import com.loopone.loopinbe.global.config.SecurityConfig;
import com.loopone.loopinbe.global.config.WebConfig;
import com.loopone.loopinbe.global.security.JwtAuthenticationFilter;
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
import com.loopone.loopinbe.global.alert.RealTimeAlertSender;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = NotificationController.class,
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
class NotificationControllerTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    // ===== controller deps =====
    @MockitoBean RealTimeAlertSender realTimeAlertSender;
    @MockitoBean NotificationService notificationService;

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
    @DisplayName("GET /rest-api/v1/notification → 200 OK (첫 페이지, cursor 없음)")
    void getNotifications_firstPage_success() throws Exception {
        // given
        NotificationResponse resp = NotificationResponse.builder()
                .id("64a1b2c3d4e5f6a7b8c9d0e1")
                .senderId(2L)
                .senderNickname("sender")
                .senderProfileUrl("http://img")
                .receiverId(1L)
                .objectId(10L)
                .content("content")
                .isRead(false)
                .targetObject(Notification.TargetObject.Follow)
                .createdAt(Instant.now())
                .build();
        given(notificationService.getNotifications(any(), eq(10), any()))
                .willReturn(PageResponse.of(List.of(resp), 10, false, null));

        // when & then
        mvc.perform(get("/rest-api/v1/notification")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data[0].id").value("64a1b2c3d4e5f6a7b8c9d0e1"))
                .andExpect(jsonPath("$.data[0].senderId").value(2L))
                .andExpect(jsonPath("$.data[0].content").value("content"))
                .andExpect(jsonPath("$.page.hasNext").value(false));
        then(notificationService).should().getNotifications(
                eq(null), eq(10), eq(currentUser)
        );
    }

    @Test
    @DisplayName("GET /rest-api/v1/notification?cursor=xxx → 200 OK (커서 페이지)")
    void getNotifications_withCursor_success() throws Exception {
        // given
        String cursor = "someCursorValue";
        given(notificationService.getNotifications(eq(cursor), eq(20), any()))
                .willReturn(PageResponse.of(List.of(), 20, false, null));

        // when & then
        mvc.perform(get("/rest-api/v1/notification")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.hasNext").value(false));
        then(notificationService).should().getNotifications(
                eq(cursor), eq(20), eq(currentUser)
        );
    }

    @Test
    @DisplayName("PATCH /rest-api/v1/notification → 200 OK")
    void markAsRead_success() throws Exception {
        // given
        NotificationRequest req = new NotificationRequest(List.of("id1", "id2"));

        // when & then
        mvc.perform(patch("/rest-api/v1/notification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist());
        then(notificationService).should().markAsRead(
                argThat(r -> r.notificationIdList().equals(List.of("id1", "id2"))),
                org.mockito.ArgumentMatchers.eq(currentUser)
        );
    }
}
