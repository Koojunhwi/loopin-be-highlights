package com.loopone.loopinbe.domain.chat.chatMessage.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatMessagePayload;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.AiChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.res.TeamChatMessageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessagePage;
import com.loopone.loopinbe.domain.chat.chatMessage.mapper.ChatMessageMapper;
import com.loopone.loopinbe.domain.chat.chatMessage.repository.ChatMessageMongoRepository;
import com.loopone.loopinbe.domain.chat.chatMessage.serviceImpl.ChatMessageServiceImpl;
import com.loopone.loopinbe.domain.chat.chatRoom.repository.ChatRoomRepository;
import com.loopone.loopinbe.global.common.response.PageResponse;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatAttachment;
import com.loopone.loopinbe.domain.chat.chatMessage.enums.AttachmentType;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.loopone.loopinbe.global.kafka.event.chatMessage.ChatMessageEventPublisher;
import com.loopone.loopinbe.global.s3.S3Service;
import com.loopone.loopinbe.global.webSocket.payload.ChatWebSocketPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;
import static com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType.MESSAGE;
import static com.loopone.loopinbe.domain.chat.chatMessage.entity.type.MessageType.ATTACHMENT;

@ExtendWith(SpringExtension.class)
public class ChatMessageServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatMessageMongoRepository chatMessageMongoRepository;
    @Mock private ChatMessageMapper chatMessageMapper;
    @Mock private S3Service s3Service;
    @Mock private ChatMessageEventPublisher chatMessageEventPublisher;

    @InjectMocks
    private ChatMessageServiceImpl chatMessageService;

    private MemberPrincipal currentUser;

    @BeforeEach
    void setUp() {
        currentUser = new MemberPrincipal(
                1L, "test@test.com", "pw", "user1", "010-0000-0000",
                Member.Gender.MALE, LocalDate.of(1999, 1, 1),
                null, Member.State.NORMAL, Member.MemberRole.ROLE_USER,
                Member.OAuthProvider.KAKAO, null
        );
    }

    private ChatMessage fakeChatMessage(String id, Long chatRoomId, Long memberId) {
        ChatMessage msg = ChatMessage.builder()
                .clientMessageId(UUID.randomUUID())
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .content("hello")
                .authorType(ChatMessage.AuthorType.USER)
                .build();
        msg.setId(id);
        msg.setCreatedAt(Instant.now());
        msg.setModifiedAt(Instant.now());
        return msg;
    }

    // ==================== getAiChatMessage ====================
    @Nested
    @DisplayName("getAiChatMessage")
    class GetAiChatMessage {

        @Test
        @DisplayName("성공 - 첫 페이지 조회")
        void success_firstPage() {
            // given
            Long chatRoomId = 10L;
            int size = 20;
            ChatMessage msg = fakeChatMessage("msg-1", chatRoomId, 1L);

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(chatRoomRepository.existsByIdAndIsBotRoom(chatRoomId, true)).willReturn(true);
            given(chatMessageMongoRepository.findByChatRoomIdFirstPage(eq(chatRoomId), any(PageRequest.class)))
                    .willReturn(List.of(msg));
            given(chatMessageMapper.loadMembers(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toAiChatMessageResponse(any(ChatMessage.class), anyMap()))
                    .willReturn(AiChatMessageResponse.builder().id("msg-1").content("hello").build());

            // when
            PageResponse<AiChatMessageResponse> result = chatMessageService.getAiChatMessage(chatRoomId, null, size, currentUser);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo("msg-1");
        }

        @Test
        @DisplayName("실패 - 참여자가 아닌 경우 NOT_AUTHORIZED")
        void fail_notMember() {
            // given
            given(chatRoomRepository.existsMember(10L, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatMessageService.getAiChatMessage(10L, null, 20, currentUser))
                    .isInstanceOf(ServiceException.class);
        }

        @Test
        @DisplayName("실패 - 페이지 사이즈 초과 시 PAGE_REQUEST_FAIL")
        void fail_pageSizeExceeded() {
            // given
            int oversized = ChatMessagePage.getMaxPageSize() + 1;

            // when & then
            assertThatThrownBy(() -> chatMessageService.getAiChatMessage(10L, null, oversized, currentUser))
                    .isInstanceOf(ServiceException.class);
        }

        @Test
        @DisplayName("실패 - AI 채팅방이 아닌 경우 NOT_AUTHORIZED")
        void fail_notBotRoom() {
            // given
            given(chatRoomRepository.existsMember(10L, 1L)).willReturn(true);
            given(chatRoomRepository.existsByIdAndIsBotRoom(10L, true)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatMessageService.getAiChatMessage(10L, null, 20, currentUser))
                    .isInstanceOf(ServiceException.class);
        }
    }

    // ==================== getTeamChatMessage ====================
    @Nested
    @DisplayName("getTeamChatMessage")
    class GetTeamChatMessage {

        @Test
        @DisplayName("성공 - 첫 페이지 조회")
        void success_firstPage() {
            // given
            Long chatRoomId = 20L;
            int size = 20;
            ChatMessage msg = fakeChatMessage("msg-2", chatRoomId, 1L);

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(chatRoomRepository.existsByIdAndIsBotRoom(chatRoomId, false)).willReturn(true);
            given(chatMessageMongoRepository.findByChatRoomIdFirstPage(eq(chatRoomId), any(PageRequest.class)))
                    .willReturn(List.of(msg));
            given(chatMessageMapper.loadMembers(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toTeamChatMessageResponse(any(ChatMessage.class), anyMap(), eq(1L)))
                    .willReturn(TeamChatMessageResponse.builder().id("msg-2").content("hello").build());

            // when
            PageResponse<TeamChatMessageResponse> result = chatMessageService.getTeamChatMessage(chatRoomId, null, size, currentUser);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo("msg-2");
        }

        @Test
        @DisplayName("실패 - 참여자가 아닌 경우 NOT_AUTHORIZED")
        void fail_notMember() {
            // given
            given(chatRoomRepository.existsMember(20L, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatMessageService.getTeamChatMessage(20L, null, 20, currentUser))
                    .isInstanceOf(ServiceException.class);
        }
    }

    // ==================== searchByKeyword ====================
    @Nested
    @DisplayName("searchByKeyword")
    class SearchByKeyword {

        @Test
        @DisplayName("성공 - 키워드 검색")
        void success() {
            // given
            Long chatRoomId = 10L;
            ChatMessage msg = fakeChatMessage("msg-3", chatRoomId, 1L);

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(chatMessageMongoRepository.searchByKeyword(eq(chatRoomId), eq("hello"), isNull(), isNull(), eq(21)))
                    .willReturn(List.of(msg));
            given(chatMessageMapper.loadMembers(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toTeamChatMessageResponse(any(ChatMessage.class), anyMap(), eq(1L)))
                    .willReturn(TeamChatMessageResponse.builder().id("msg-3").content("hello").build());

            // when
            PageResponse<TeamChatMessageResponse> result = chatMessageService.searchByKeyword(chatRoomId, "hello", null, 20, currentUser);

            // then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("실패 - 참여자가 아닌 경우 NOT_AUTHORIZED")
        void fail_notMember() {
            // given
            given(chatRoomRepository.existsMember(10L, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatMessageService.searchByKeyword(10L, "hello", null, 20, currentUser))
                    .isInstanceOf(ServiceException.class);
        }
    }

    // ==================== processInbound ====================
    @Nested
    @DisplayName("processInbound")
    class ProcessInbound {

        @Test
        @DisplayName("성공 - 메시지 저장 및 반환")
        void success() {
            // given
            UUID clientMsgId = UUID.randomUUID();
            Instant now = Instant.now();
            ChatMessagePayload inPayload = new ChatMessagePayload(
                    "u:" + clientMsgId, clientMsgId, 10L, 1L,
                    "hello", null, null, null, null,
                    ChatMessage.AuthorType.USER, true, false, now, now
            );
            ChatMessage saved = fakeChatMessage("u:" + clientMsgId, 10L, 1L);

            given(chatRoomRepository.findIsBotRoom(10L)).willReturn(true);
            given(chatMessageMongoRepository.upsertInbound(
                    anyString(), anyString(), anyLong(), anyLong(),
                    anyString(), isNull(), isNull(), isNull(), isNull(),
                    any(), any(), any()
            )).willReturn(saved);
            given(chatRoomRepository.updateLastMessageAtIfNewer(anyLong(), any())).willReturn(1);

            // when
            ChatMessagePayload result = chatMessageService.processInbound(inPayload);

            // then
            assertThat(result).isNotNull();
            assertThat(result.chatRoomId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("실패 - 채팅방을 찾을 수 없는 경우 CHATROOM_NOT_FOUND")
        void fail_chatRoomNotFound() {
            // given
            UUID clientMsgId = UUID.randomUUID();
            Instant now = Instant.now();
            ChatMessagePayload inPayload = new ChatMessagePayload(
                    "u:" + clientMsgId, clientMsgId, 999L, 1L,
                    "hello", null, null, null, null,
                    ChatMessage.AuthorType.USER, true, false, now, now
            );

            given(chatRoomRepository.findIsBotRoom(999L)).willReturn(null);

            // when & then
            assertThatThrownBy(() -> chatMessageService.processInbound(inPayload))
                    .isInstanceOf(ServiceException.class);
        }
    }

    // ==================== deleteChatMessage ====================
    @Nested
    @DisplayName("deleteChatMessage")
    class DeleteChatMessage {

        @Test
        @DisplayName("성공 - 메시지 소프트 삭제")
        void success() {
            // given
            ChatMessage msg = fakeChatMessage("msg-1", 10L, 1L);
            given(chatMessageMongoRepository.findById("msg-1")).willReturn(Optional.of(msg));

            // when
            chatMessageService.deleteChatMessage("msg-1", 1L);

            // then
            then(chatMessageMongoRepository).should().save(msg);
            assertThat(msg.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("실패 - 메시지가 존재하지 않는 경우 CHATMESSAGE_NOT_FOUND")
        void fail_notFound() {
            // given
            given(chatMessageMongoRepository.findById("no-msg")).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatMessageService.deleteChatMessage("no-msg", 1L))
                    .isInstanceOf(ServiceException.class);
        }

        @Test
        @DisplayName("실패 - 본인의 메시지가 아닌 경우 NOT_AUTHORIZED")
        void fail_notAuthorized() {
            // given
            ChatMessage msg = fakeChatMessage("msg-1", 10L, 2L);
            given(chatMessageMongoRepository.findById("msg-1")).willReturn(Optional.of(msg));

            // when & then
            assertThatThrownBy(() -> chatMessageService.deleteChatMessage("msg-1", 1L))
                    .isInstanceOf(ServiceException.class);
        }
    }

    // ==================== deleteAllChatMessages ====================
    @Nested
    @DisplayName("deleteAllChatMessages")
    class DeleteAllChatMessages {

        @Test
        @DisplayName("성공 - 채팅방 전체 메시지 소프트 삭제")
        void success() {
            // when
            chatMessageService.deleteAllChatMessages(10L);

            // then
            then(chatMessageMongoRepository).should().softDeleteByChatRoomId(10L);
        }
    }

    // ==================== deleteRecommendationMessage ====================
    @Nested
    @DisplayName("deleteRecommendationMessage")
    class DeleteRecommendationMessage {

        @Test
        @DisplayName("성공 - 추천 메시지가 존재하면 삭제 후 ID 반환")
        void success_found() {
            // given
            ChatMessage msg = ChatMessage.builder()
                    .chatRoomId(10L)
                    .authorType(ChatMessage.AuthorType.BOT)
                    .recommendations(List.of())
                    .build();
            msg.setId("rec-msg-1");
            msg.setCreatedAt(Instant.now());
            // recommendations가 empty가 아닌 경우를 테스트해야 함
            ChatMessage msgWithRec = ChatMessage.builder()
                    .chatRoomId(10L)
                    .authorType(ChatMessage.AuthorType.BOT)
                    .recommendations(List.of(new com.loopone.loopinbe.domain.loop.loop.dto.req.LoopCreateRequest(
                            "title", "content", null, null, null, null, null, null, null
                    )))
                    .build();
            msgWithRec.setId("rec-msg-2");
            msgWithRec.setCreatedAt(Instant.now());

            given(chatMessageMongoRepository.findByChatRoomIdFirstPage(eq(10L), any(PageRequest.class)))
                    .willReturn(List.of(msg, msgWithRec));

            // when
            String result = chatMessageService.deleteRecommendationMessage(10L);

            // then
            assertThat(result).isEqualTo("rec-msg-2");
            then(chatMessageMongoRepository).should().save(msgWithRec);
        }

        @Test
        @DisplayName("성공 - 추천 메시지가 없으면 null 반환")
        void success_notFound() {
            // given
            given(chatMessageMongoRepository.findByChatRoomIdFirstPage(eq(10L), any(PageRequest.class)))
                    .willReturn(List.of());

            // when
            String result = chatMessageService.deleteRecommendationMessage(10L);

            // then
            assertThat(result).isNull();
            then(chatMessageMongoRepository).should(never()).save(any());
        }
    }

    // ==================== sendAttachment ====================
    @Nested
    @DisplayName("sendAttachment")
    class SendAttachment {

        private final Long chatRoomId = 10L;

        private MockMultipartFile fakeImage(String name) {
            return new MockMultipartFile("images", name, "image/png", "fake-image".getBytes());
        }

        private MockMultipartFile fakeFile(String name) {
            return new MockMultipartFile("files", name, "application/pdf", "fake-file".getBytes());
        }

        private ChatAttachment imageAttachment(String name) {
            return new ChatAttachment(AttachmentType.IMAGE, "chat-images/uuid_" + name, name, "image/png", 10);
        }

        private ChatAttachment fileAttachment(String name) {
            return new ChatAttachment(AttachmentType.FILE, "chat-files/uuid_" + name, name, "application/pdf", 10);
        }

        private ChatMessage fakeSavedMessage(UUID clientMessageId, List<ChatAttachment> attachments) {
            ChatMessage msg = ChatMessage.builder()
                    .clientMessageId(clientMessageId)
                    .chatRoomId(chatRoomId)
                    .memberId(1L)
                    .attachments(attachments)
                    .authorType(ChatMessage.AuthorType.USER)
                    .build();
            msg.setId("u:" + clientMessageId);
            msg.setCreatedAt(Instant.now());
            msg.setModifiedAt(Instant.now());
            return msg;
        }

        /** processInbound 내부 의존성 공통 스텁 */
        private void stubProcessInbound(UUID clientMessageId, List<ChatAttachment> attachments) {
            ChatMessage saved = fakeSavedMessage(clientMessageId, attachments);
            given(chatRoomRepository.findIsBotRoom(chatRoomId)).willReturn(false);
            given(chatMessageMongoRepository.upsertInbound(
                    anyString(), anyString(), anyLong(), anyLong(),
                    isNull(), anyList(), isNull(), isNull(), isNull(),
                    any(), any(), any()
            )).willReturn(saved);
            given(chatRoomRepository.updateLastMessageAtIfNewer(anyLong(), any())).willReturn(1);
        }

        @Test
        @DisplayName("성공 - 이미지만 전송")
        void success_imagesOnly() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            List<MultipartFile> images = List.of(fakeImage("a.png"), fakeImage("b.png"));
            ChatAttachment att = imageAttachment("a.png");

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(s3Service.uploadChatImage(any(), eq("chat-images"))).willReturn(att);
            stubProcessInbound(clientMessageId, List.of(att, att));

            given(chatMessageMapper.loadMembersFromPayload(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toTeamChatMessageResponse(any(ChatMessagePayload.class), anyMap(), eq(1L)))
                    .willReturn(TeamChatMessageResponse.builder().id("u:" + clientMessageId).build());

            // when
            chatMessageService.sendAttachment(chatRoomId, clientMessageId, images, null, currentUser);

            // then
            then(s3Service).should(times(2)).uploadChatImage(any(), eq("chat-images"));
            then(s3Service).should(never()).uploadChatFile(any(), anyString());
            then(chatMessageEventPublisher).should().publishWsEvent(any(ChatWebSocketPayload.class));
        }

        @Test
        @DisplayName("성공 - 파일만 전송")
        void success_filesOnly() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            List<MultipartFile> files = List.of(fakeFile("doc.pdf"));
            ChatAttachment att = fileAttachment("doc.pdf");

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(s3Service.uploadChatFile(any(), eq("chat-files"))).willReturn(att);
            stubProcessInbound(clientMessageId, List.of(att));

            given(chatMessageMapper.loadMembersFromPayload(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toTeamChatMessageResponse(any(ChatMessagePayload.class), anyMap(), eq(1L)))
                    .willReturn(TeamChatMessageResponse.builder().id("u:" + clientMessageId).build());

            // when
            chatMessageService.sendAttachment(chatRoomId, clientMessageId, null, files, currentUser);

            // then
            then(s3Service).should(never()).uploadChatImage(any(), anyString());
            then(s3Service).should(times(1)).uploadChatFile(any(), eq("chat-files"));
            then(chatMessageEventPublisher).should().publishWsEvent(any(ChatWebSocketPayload.class));
        }

        @Test
        @DisplayName("성공 - 이미지 + 파일 동시 전송")
        void success_imagesAndFiles() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            List<MultipartFile> images = List.of(fakeImage("a.png"));
            List<MultipartFile> files = List.of(fakeFile("doc.pdf"));

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(s3Service.uploadChatImage(any(), eq("chat-images"))).willReturn(imageAttachment("a.png"));
            given(s3Service.uploadChatFile(any(), eq("chat-files"))).willReturn(fileAttachment("doc.pdf"));
            stubProcessInbound(clientMessageId, List.of(imageAttachment("a.png"), fileAttachment("doc.pdf")));

            given(chatMessageMapper.loadMembersFromPayload(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toTeamChatMessageResponse(any(ChatMessagePayload.class), anyMap(), eq(1L)))
                    .willReturn(TeamChatMessageResponse.builder().id("u:" + clientMessageId).build());

            // when
            chatMessageService.sendAttachment(chatRoomId, clientMessageId, images, files, currentUser);

            // then
            then(s3Service).should().uploadChatImage(any(), eq("chat-images"));
            then(s3Service).should().uploadChatFile(any(), eq("chat-files"));

            ArgumentCaptor<ChatWebSocketPayload> captor = ArgumentCaptor.forClass(ChatWebSocketPayload.class);
            then(chatMessageEventPublisher).should().publishWsEvent(captor.capture());
            assertThat(captor.getValue().getMessageType()).isEqualTo(ATTACHMENT);
            assertThat(captor.getValue().getChatRoomId()).isEqualTo(chatRoomId);
        }

        @Test
        @DisplayName("성공 - null/빈 파일은 건너뛰고 유효한 파일만 업로드")
        void success_skipsEmptyFiles() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            MockMultipartFile emptyFile = new MockMultipartFile("images", "empty.png", "image/png", new byte[0]);
            List<MultipartFile> images = List.of(emptyFile, fakeImage("valid.png"));
            ChatAttachment att = imageAttachment("valid.png");

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(s3Service.uploadChatImage(any(), eq("chat-images"))).willReturn(att);
            stubProcessInbound(clientMessageId, List.of(att));

            given(chatMessageMapper.loadMembersFromPayload(anyList())).willReturn(Map.of());
            given(chatMessageMapper.toTeamChatMessageResponse(any(ChatMessagePayload.class), anyMap(), eq(1L)))
                    .willReturn(TeamChatMessageResponse.builder().id("u:" + clientMessageId).build());

            // when
            chatMessageService.sendAttachment(chatRoomId, clientMessageId, images, null, currentUser);

            // then - 빈 파일은 건너뛰고 유효한 파일 1개만 업로드
            then(s3Service).should(times(1)).uploadChatImage(any(), eq("chat-images"));
        }

        @Test
        @DisplayName("실패 - 참여자가 아닌 경우 NOT_AUTHORIZED")
        void fail_notMember() {
            // given
            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatMessageService.sendAttachment(chatRoomId, UUID.randomUUID(), null, null, currentUser))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getReturnCode())
                    .isEqualTo(ReturnCode.NOT_AUTHORIZED);
        }

        @Test
        @DisplayName("실패 - 파일이 없는 경우 FILE_UPLOAD_ERROR")
        void fail_noFiles() {
            // given
            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> chatMessageService.sendAttachment(chatRoomId, UUID.randomUUID(), null, null, currentUser))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getReturnCode())
                    .isEqualTo(ReturnCode.FILE_UPLOAD_ERROR);
        }

        @Test
        @DisplayName("실패 - 이미지 개수 초과 (10개 초과) → MAX_FILE_LIMIT_EXCEEDED")
        void fail_imagesExceedMaxCount() {
            // given
            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            List<MultipartFile> images = new ArrayList<>();
            for (int i = 0; i < 11; i++) {
                images.add(fakeImage("img" + i + ".png"));
            }

            // when & then
            assertThatThrownBy(() -> chatMessageService.sendAttachment(chatRoomId, UUID.randomUUID(), images, null, currentUser))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getReturnCode())
                    .isEqualTo(ReturnCode.MAX_FILE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("실패 - 파일 크기 초과 (30MB 초과) → MAX_FILE_SIZE_LIMIT_EXCEEDED")
        void fail_fileSizeExceeded() {
            // given
            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            MultipartFile bigFile = mock(MultipartFile.class);
            given(bigFile.isEmpty()).willReturn(false);
            given(bigFile.getSize()).willReturn(31L * 1024 * 1024);

            // when & then
            assertThatThrownBy(() -> chatMessageService.sendAttachment(chatRoomId, UUID.randomUUID(), null, List.of(bigFile), currentUser))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getReturnCode())
                    .isEqualTo(ReturnCode.MAX_FILE_SIZE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("실패 - S3 업로드 IOException 발생 시 이미 업로드된 파일 보상 삭제")
        void fail_s3IoException_rollbackUploaded() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            List<MultipartFile> images = List.of(fakeImage("a.png"), fakeImage("b.png"));
            ChatAttachment uploaded = imageAttachment("a.png");

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(s3Service.uploadChatImage(any(), eq("chat-images")))
                    .willReturn(uploaded)                           // 첫 번째 성공
                    .willThrow(new IOException("S3 connection lost")); // 두 번째 실패

            // when & then
            assertThatThrownBy(() -> chatMessageService.sendAttachment(chatRoomId, clientMessageId, images, null, currentUser))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getReturnCode())
                    .isEqualTo(ReturnCode.INTERNAL_ERROR);

            // 보상 삭제: 첫 번째 업로드된 파일의 S3 key로 삭제 호출
            then(s3Service).should().deleteAllByKeys(List.of(uploaded.key()));
        }

        @Test
        @DisplayName("실패 - processInbound 실패 시 S3 보상 삭제")
        void fail_processInboundFails_rollbackS3() throws Exception {
            // given
            UUID clientMessageId = UUID.randomUUID();
            List<MultipartFile> images = List.of(fakeImage("a.png"));
            ChatAttachment uploaded = imageAttachment("a.png");

            given(chatRoomRepository.existsMember(chatRoomId, 1L)).willReturn(true);
            given(s3Service.uploadChatImage(any(), eq("chat-images"))).willReturn(uploaded);
            // processInbound가 실패하도록 설정
            given(chatRoomRepository.findIsBotRoom(chatRoomId)).willReturn(null);

            // when & then
            assertThatThrownBy(() -> chatMessageService.sendAttachment(chatRoomId, clientMessageId, images, null, currentUser))
                    .isInstanceOf(ServiceException.class);

            // S3 보상 삭제 확인
            then(s3Service).should().deleteAllByKeys(List.of(uploaded.key()));
        }
    }
}
