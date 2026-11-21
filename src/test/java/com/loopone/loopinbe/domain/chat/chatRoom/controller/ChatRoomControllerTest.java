package com.loopone.loopinbe.domain.chat.chatRoom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.chat.chatRoom.dto.res.ChatRoomListResponse;
import com.loopone.loopinbe.domain.chat.chatRoom.dto.res.ChatRoomResponse;
import com.loopone.loopinbe.domain.chat.chatRoom.enums.ChatRoomType;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomService;
import com.loopone.loopinbe.global.config.SecurityConfig;
import com.loopone.loopinbe.global.config.WebConfig;
import com.loopone.loopinbe.global.security.JwtAuthenticationFilter;
import com.loopone.loopinbe.global.alert.RealTimeAlertSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ChatRoomController.class,
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
class ChatRoomControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RealTimeAlertSender realTimeAlertSender;

    @MockitoBean
    private ChatRoomService chatRoomService;

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

    @Nested
    @DisplayName("GET /rest-api/v1/chat-room")
    class GetChatRooms {

        @Test
        @DisplayName("채팅방 리스트 조회 성공")
        void success() throws Exception {
            // given
            ChatRoomListResponse fakeResponse = new ChatRoomListResponse(List.of());

            given(chatRoomService.getChatRooms(any(), any()))
                    .willReturn(fakeResponse);

            // when & then
            mvc.perform(get("/rest-api/v1/chat-room")
                            .param("chatRoomType", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("POST /rest-api/v1/chat-room/create")
    class CreateChatRoom {

        @Test
        @DisplayName("AI 채팅방 생성 성공")
        void success() throws Exception {
            // given
            ChatRoomResponse fakeResponse = ChatRoomResponse.builder()
                    .id(10L)
                    .ownerId(1L)
                    .title("AI Chat")
                    .build();

            given(chatRoomService.createAiChatRoom(any(MemberPrincipal.class)))
                    .willReturn(fakeResponse);

            // when & then
            mvc.perform(post("/rest-api/v1/chat-room/create"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(10))
                    .andExpect(jsonPath("$.data.title").value("AI Chat"));

            then(chatRoomService).should().createAiChatRoom(any(MemberPrincipal.class));
        }
    }

    @Nested
    @DisplayName("GET /rest-api/v1/chat-room/team/{teamId}")
    class GetChatRoomByTeamId {

        @Test
        @DisplayName("팀 채팅방 조회 성공")
        void success() throws Exception {
            // given
            ChatRoomResponse fakeResponse = ChatRoomResponse.builder()
                    .id(20L)
                    .ownerId(1L)
                    .title("Team Chat")
                    .build();

            given(chatRoomService.findChatRoomByTeamId(eq(5L), any(MemberPrincipal.class)))
                    .willReturn(fakeResponse);

            // when & then
            mvc.perform(get("/rest-api/v1/chat-room/team/{teamId}", 5L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(20))
                    .andExpect(jsonPath("$.data.title").value("Team Chat"));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class WebMvcTestConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(java.util.List<org.springframework.web.method.support.HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver());
        }
    }

}
