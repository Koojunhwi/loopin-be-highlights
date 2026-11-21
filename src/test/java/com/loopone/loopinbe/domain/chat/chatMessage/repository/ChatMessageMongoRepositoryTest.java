package com.loopone.loopinbe.domain.chat.chatMessage.repository;

import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatAttachment;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import com.loopone.loopinbe.domain.chat.chatMessage.enums.AttachmentType;
import com.loopone.loopinbe.domain.chat.chatMessage.repositoryImpl.ChatMessageMongoRepositoryImpl;
import com.loopone.loopinbe.global.config.MongoAuditingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
@Testcontainers
@Import({ChatMessageMongoRepositoryImpl.class, MongoAuditingConfig.class})
class ChatMessageMongoRepositoryTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    ChatMessageMongoRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    // ===== Helpers =====

    private ChatMessage saveMessage(Long chatRoomId, Long memberId, String content, Instant createdAt) {
        ChatMessage msg = ChatMessage.builder()
                .clientMessageId(UUID.randomUUID())
                .chatRoomId(chatRoomId)
                .memberId(memberId)
                .content(content)
                .authorType(ChatMessage.AuthorType.USER)
                .build();
        msg.setCreatedAt(createdAt);
        msg.setModifiedAt(createdAt);
        return repository.save(msg);
    }

    private ChatMessage saveDeletedMessage(Long chatRoomId, String content, Instant createdAt) {
        return saveDeletedMessage(chatRoomId, content, createdAt, Instant.now());
    }

    private ChatMessage saveDeletedMessage(Long chatRoomId, String content, Instant createdAt, Instant deletedAt) {
        ChatMessage msg = ChatMessage.builder()
                .clientMessageId(UUID.randomUUID())
                .chatRoomId(chatRoomId)
                .memberId(1L)
                .content(content)
                .authorType(ChatMessage.AuthorType.USER)
                .build();
        msg.setCreatedAt(createdAt);
        msg.setModifiedAt(createdAt);
        msg.setDeletedAt(deletedAt);
        return repository.save(msg);
    }

    // ==================== findByChatRoomIdFirstPage ====================
    @Nested
    @DisplayName("findByChatRoomIdFirstPage")
    class FindByChatRoomIdFirstPage {

        @Test
        @DisplayName("성공 - 최신순으로 size만큼 조회")
        void success() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "old", base.minus(2, ChronoUnit.SECONDS));
            saveMessage(1L, 1L, "mid", base.minus(1, ChronoUnit.SECONDS));
            saveMessage(1L, 1L, "new", base);

            List<ChatMessage> result = repository.findByChatRoomIdFirstPage(1L, PageRequest.of(0, 2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getContent()).isEqualTo("new");
            assertThat(result.get(1).getContent()).isEqualTo("mid");
        }

        @Test
        @DisplayName("성공 - 삭제된 메시지 제외")
        void excludesDeleted() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "alive", base);
            saveDeletedMessage(1L, "deleted", base.minus(1, ChronoUnit.SECONDS));

            List<ChatMessage> result = repository.findByChatRoomIdFirstPage(1L, PageRequest.of(0, 10));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("alive");
        }

        @Test
        @DisplayName("성공 - 다른 채팅방 메시지는 포함하지 않음")
        void isolatedByChatRoom() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "room1", base);
            saveMessage(2L, 1L, "room2", base);

            List<ChatMessage> result = repository.findByChatRoomIdFirstPage(1L, PageRequest.of(0, 10));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("room1");
        }
    }

    // ==================== findByChatRoomIdWithCursor ====================
    @Nested
    @DisplayName("findByChatRoomIdWithCursor")
    class FindByChatRoomIdWithCursor {

        @Test
        @DisplayName("성공 - 커서 이전 메시지만 조회")
        void success() {
            Instant base = Instant.now();
            ChatMessage msg1 = saveMessage(1L, 1L, "first", base.minus(3, ChronoUnit.SECONDS));
            ChatMessage msg2 = saveMessage(1L, 1L, "second", base.minus(2, ChronoUnit.SECONDS));
            ChatMessage cursor = saveMessage(1L, 1L, "cursor", base.minus(1, ChronoUnit.SECONDS));
            saveMessage(1L, 1L, "latest", base);

            List<ChatMessage> result = repository.findByChatRoomIdWithCursor(
                    1L, cursor.getCreatedAt(), cursor.getId(), PageRequest.of(0, 10));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getContent()).isEqualTo("second");
            assertThat(result.get(1).getContent()).isEqualTo("first");
        }
    }

    // ==================== upsertInbound ====================
    @Nested
    @DisplayName("upsertInbound")
    class UpsertInbound {

        @Test
        @DisplayName("성공 - 새 메시지 삽입")
        void insertNew() {
            UUID clientMsgId = UUID.randomUUID();
            String id = "u:" + clientMsgId;
            Instant now = Instant.now();

            ChatMessage result = repository.upsertInbound(
                    id, clientMsgId.toString(), 1L, 1L,
                    "hello", null, null, null, null,
                    ChatMessage.AuthorType.USER, now, now);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(id);
            assertThat(result.getContent()).isEqualTo("hello");
            assertThat(result.getChatRoomId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("성공 - 동일 ID로 재전송 시 멱등성 보장 (기존 값 유지)")
        void idempotent() {
            UUID clientMsgId = UUID.randomUUID();
            String id = "u:" + clientMsgId;
            Instant now = Instant.now();

            repository.upsertInbound(
                    id, clientMsgId.toString(), 1L, 1L,
                    "original", null, null, null, null,
                    ChatMessage.AuthorType.USER, now, now);

            ChatMessage result = repository.upsertInbound(
                    id, clientMsgId.toString(), 1L, 1L,
                    "duplicate", null, null, null, null,
                    ChatMessage.AuthorType.USER, now, now);

            assertThat(result.getContent()).isEqualTo("original");
            assertThat(repository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("성공 - 첨부파일 포함 메시지 삽입")
        void withAttachments() {
            UUID clientMsgId = UUID.randomUUID();
            String id = "u:" + clientMsgId;
            Instant now = Instant.now();
            List<ChatAttachment> attachments = List.of(
                    new ChatAttachment(AttachmentType.IMAGE, "chat-images/img.png", "img.png", "image/png", 1024)
            );

            ChatMessage result = repository.upsertInbound(
                    id, clientMsgId.toString(), 1L, 1L,
                    null, attachments, null, null, null,
                    ChatMessage.AuthorType.USER, now, now);

            assertThat(result.getAttachments()).hasSize(1);
            assertThat(result.getAttachments().get(0).key()).isEqualTo("chat-images/img.png");
        }
    }

    // ==================== softDeleteByChatRoomId ====================
    @Nested
    @DisplayName("softDeleteByChatRoomId")
    class SoftDeleteByChatRoomId {

        @Test
        @DisplayName("성공 - 해당 채팅방 메시지 전체 소프트 삭제")
        void success() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "msg1", base);
            saveMessage(1L, 2L, "msg2", base);
            saveMessage(2L, 1L, "other-room", base);

            long deleted = repository.softDeleteByChatRoomId(1L);

            assertThat(deleted).isEqualTo(2);
            // 소프트 삭제된 메시지는 firstPage 조회에서 제외
            assertThat(repository.findByChatRoomIdFirstPage(1L, PageRequest.of(0, 10))).isEmpty();
            // 다른 채팅방은 영향 없음
            assertThat(repository.findByChatRoomIdFirstPage(2L, PageRequest.of(0, 10))).hasSize(1);
        }

        @Test
        @DisplayName("성공 - 이미 삭제된 메시지는 재삭제하지 않음")
        void skipsAlreadyDeleted() {
            Instant base = Instant.now();
            saveDeletedMessage(1L, "already-deleted", base);
            saveMessage(1L, 1L, "alive", base);

            long deleted = repository.softDeleteByChatRoomId(1L);

            assertThat(deleted).isEqualTo(1);
        }
    }

    // ==================== purgeDeletedBefore ====================
    @Nested
    @DisplayName("purgeDeletedBefore")
    class PurgeDeletedBefore {

        @Test
        @DisplayName("성공 - threshold 이전에 삭제된 메시지만 물리 삭제")
        void success() {
            Instant base = Instant.now();
            Instant threshold = base.minus(1, ChronoUnit.DAYS);
            // 오래 전 삭제된 메시지 (deletedAt이 threshold 이전)
            ChatMessage old = saveDeletedMessage(1L, "old-deleted", base.minus(10, ChronoUnit.DAYS), base.minus(10, ChronoUnit.DAYS));
            // 최근 삭제된 메시지 (deletedAt이 threshold 이후)
            ChatMessage recent = saveDeletedMessage(1L, "recent-deleted", base, base);
            // 삭제되지 않은 메시지
            saveMessage(1L, 1L, "alive", base);

            long purged = repository.purgeDeletedBefore(threshold);

            assertThat(purged).isEqualTo(1);
            assertThat(repository.findById(old.getId())).isEmpty();
            assertThat(repository.findById(recent.getId())).isPresent();
        }
    }

    // ==================== searchByKeyword ====================
    @Nested
    @DisplayName("searchByKeyword")
    class SearchByKeyword {

        @Test
        @DisplayName("성공 - 키워드가 포함된 메시지 검색")
        void success() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "안녕하세요", base.minus(2, ChronoUnit.SECONDS));
            saveMessage(1L, 1L, "반갑습니다", base.minus(1, ChronoUnit.SECONDS));
            saveMessage(1L, 1L, "안녕히 가세요", base);

            List<ChatMessage> result = repository.searchByKeyword(1L, "안녕", null, null, 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getContent()).isEqualTo("안녕히 가세요");
            assertThat(result.get(1).getContent()).isEqualTo("안녕하세요");
        }

        @Test
        @DisplayName("성공 - 삭제된 메시지는 검색 결과에서 제외")
        void excludesDeleted() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "hello world", base);
            saveDeletedMessage(1L, "hello deleted", base.minus(1, ChronoUnit.SECONDS));

            List<ChatMessage> result = repository.searchByKeyword(1L, "hello", null, null, 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("성공 - 커서 기반 페이징")
        void withCursor() {
            Instant base = Instant.now();
            ChatMessage msg1 = saveMessage(1L, 1L, "hello 1", base.minus(3, ChronoUnit.SECONDS));
            ChatMessage msg2 = saveMessage(1L, 1L, "hello 2", base.minus(2, ChronoUnit.SECONDS));
            ChatMessage cursor = saveMessage(1L, 1L, "hello 3", base.minus(1, ChronoUnit.SECONDS));
            saveMessage(1L, 1L, "hello 4", base);

            List<ChatMessage> result = repository.searchByKeyword(
                    1L, "hello", cursor.getCreatedAt(), cursor.getId(), 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getContent()).isEqualTo("hello 2");
            assertThat(result.get(1).getContent()).isEqualTo("hello 1");
        }

        @Test
        @DisplayName("성공 - 빈 키워드는 빈 결과 반환")
        void emptyKeyword() {
            saveMessage(1L, 1L, "hello", Instant.now());

            List<ChatMessage> result = repository.searchByKeyword(1L, "", null, null, 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("성공 - 다른 채팅방 메시지는 포함하지 않음")
        void isolatedByChatRoom() {
            Instant base = Instant.now();
            saveMessage(1L, 1L, "hello room1", base);
            saveMessage(2L, 1L, "hello room2", base);

            List<ChatMessage> result = repository.searchByKeyword(1L, "hello", null, null, 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("hello room1");
        }
    }
}
