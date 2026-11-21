package com.loopone.loopinbe.domain.chat.chatMessage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.req.ChatMessageRequest;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.AiChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.TeamChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType;
import com.loopone.loopinbe.domain.chat.chatMessage.service.ChatMessageService;
import com.loopone.loopinbe.global.common.response.PageResponse;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = ChatMessageController.class,
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
public class ChatMessageControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RealTimeAlertSender realTimeAlertSender;

    @MockitoBean
    private ChatMessageService chatMessageService;

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
    @DisplayName("GET /rest-api/v1/chat-message/ai/{chatRoomId}")
    class GetAiChatMessage {

        @Test
        @DisplayName("AI 채팅방 과거 메시지 조회 성공")
        void success() throws Exception {
            // given
            AiChatMessageResponse dto = AiChatMessageResponse.builder()
                    .id("msg-1")
                    .memberId(1L)
                    .nickname("jun")
                    .content("hello")
                    .createdAt(Instant.now())
                    .build();

            PageResponse<AiChatMessageResponse> pageResponse = PageResponse.of(List.of(dto), 20, false, null);

            given(chatMessageService.getAiChatMessage(eq(10L), isNull(), eq(50), any()))
                    .willReturn(pageResponse);

            // when & then
            mvc.perform(get("/rest-api/v1/chat-message/ai/{chatRoomId}", 10L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value("msg-1"))
                    .andExpect(jsonPath("$.data[0].content").value("hello"));
        }
    }

    @Nested
    @DisplayName("GET /rest-api/v1/chat-message/team/{chatRoomId}")
    class GetTeamChatMessage {

        @Test
        @DisplayName("팀 채팅방 과거 메시지 조회 성공")
        void success() throws Exception {
            // given
            TeamChatMessageResponse dto = TeamChatMessageResponse.builder()
                    .id("msg-2")
                    .memberId(1L)
                    .nickname("jun")
                    .content("team hello")
                    .isMine(true)
                    .createdAt(Instant.now())
                    .build();

            PageResponse<TeamChatMessageResponse> pageResponse = PageResponse.of(List.of(dto), 20, false, null);

            given(chatMessageService.getTeamChatMessage(eq(20L), isNull(), eq(50), any()))
                    .willReturn(pageResponse);

            // when & then
            mvc.perform(get("/rest-api/v1/chat-message/team/{chatRoomId}", 20L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value("msg-2"))
                    .andExpect(jsonPath("$.data[0].content").value("team hello"));
        }
    }

    @Nested
    @DisplayName("GET /rest-api/v1/chat-message/{chatRoomId}/search")
    class SearchChatMessage {

        @Test
        @DisplayName("채팅방 메시지 검색 성공")
        void success() throws Exception {
            // given
            TeamChatMessageResponse dto = TeamChatMessageResponse.builder()
                    .id("msg-3")
                    .memberId(1L)
                    .content("search result")
                    .createdAt(Instant.now())
                    .build();

            PageResponse<TeamChatMessageResponse> pageResponse = PageResponse.of(List.of(dto), 20, false, null);

            given(chatMessageService.searchByKeyword(eq(10L), eq("hello"), isNull(), eq(50), any()))
                    .willReturn(pageResponse);

            // when & then
            mvc.perform(get("/rest-api/v1/chat-message/{chatRoomId}/search", 10L)
                            .param("keyword", "hello"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value("msg-3"))
                    .andExpect(jsonPath("$.data[0].content").value("search result"));
        }
    }

    @Nested
    @DisplayName("POST /rest-api/v1/chat-message/{chatRoomId}/chat")
    class SendChatMessage {

        @Test
        @DisplayName("AI 채팅방 메시지 전송 성공")
        void success() throws Exception {
            // given
            ChatMessageRequest request = new ChatMessageRequest(
                    "hello AI",
                    UUID.randomUUID(),
                    MessageType.CREATE_LOOP
            );

            // when & then
            mvc.perform(post("/rest-api/v1/chat-message/{chatRoomId}/chat", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            then(chatMessageService).should().sendChatMessage(eq(10L), any(ChatMessageRequest.class), any());
        }
    }

    @Nested
    @DisplayName("POST /rest-api/v1/chat-message/attachments/{chatRoomId}")
    class SendAttachment {

        @Test
        @DisplayName("파일 메시지 전송 성공")
        void success() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            String attachmentRequestJson = objectMapper.writeValueAsString(
                    new com.loopone.loopinbe.domain.chat.chatMessage.dto.req.AttachmentRequest(clientMessageId)
            );

            MockMultipartFile attachmentRequestPart = new MockMultipartFile(
                    "attachmentRequest", "", MediaType.APPLICATION_JSON_VALUE,
                    attachmentRequestJson.getBytes()
            );

            MockMultipartFile imagePart = new MockMultipartFile(
                    "images", "test.png", MediaType.IMAGE_PNG_VALUE,
                    "fake-image".getBytes()
            );

            // when & then
            mvc.perform(multipart("/rest-api/v1/chat-message/attachments/{chatRoomId}", 10L)
                            .file(attachmentRequestPart)
                            .file(imagePart)
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            then(chatMessageService).should().sendAttachment(eq(10L), eq(clientMessageId), anyList(), isNull(), any());
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
