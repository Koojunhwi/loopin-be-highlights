package com.loopone.loopinbe.domain.account.auth.controller.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.domain.account.auth.dto.req.LoginRequest;
import com.loopone.loopinbe.domain.account.auth.dto.res.LoginResponse;
import com.loopone.loopinbe.domain.account.auth.service.AuthService;
import com.loopone.loopinbe.global.config.SecurityConfig;
import com.loopone.loopinbe.global.config.WebConfig;
import com.loopone.loopinbe.global.security.JwtAuthenticationFilter;
import com.loopone.loopinbe.global.web.cookie.WebAuthCookieFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.loopone.loopinbe.global.alert.RealTimeAlertSender;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = WebAuthDevController.class,
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
class WebAuthDevControllerTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean RealTimeAlertSender realTimeAlertSender;
    @MockitoBean AuthService authService;
    @MockitoBean WebAuthCookieFactory webAuthCookieFactory;

    @Test
    @DisplayName("POST /rest-api/v1/auth/login → 200 OK & Set-Cookie 2개")
    void login_success() throws Exception {
        // given
        var req = new LoginRequest("jun@loop.in");
        var loginResp = new LoginResponse("access-token-aaa", "refresh-token-bbb");

        given(authService.login(any(LoginRequest.class))).willReturn(loginResp);
        ResponseCookie accessCookie = ResponseCookie.from("ACCESS", "access-token-aaa").path("/").httpOnly(true).build();
        ResponseCookie refreshCookie = ResponseCookie.from("REFRESH", "refresh-token-bbb").path("/").httpOnly(true).build();
        given(webAuthCookieFactory.issueAccess("access-token-aaa")).willReturn(accessCookie);
        given(webAuthCookieFactory.issueRefresh("refresh-token-bbb")).willReturn(refreshCookie);

        // when & then
        mvc.perform(post("/rest-api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        accessCookie.toString(),
                        refreshCookie.toString()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist());
        then(authService).should().login(any(LoginRequest.class));
    }
}
