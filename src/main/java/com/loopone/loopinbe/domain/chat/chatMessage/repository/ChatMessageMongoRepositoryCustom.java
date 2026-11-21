package com.loopone.loopinbe.domain.chat.chatMessage.repository;

import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatAttachment;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import com.loopone.loopinbe.domain.loop.loop.dto.req.LoopCreateRequest;

import java.time.Instant;
import java.util.List;

public interface ChatMessageMongoRepositoryCustom {
    long softDeleteByChatRoomId(Long chatRoomId);

    long purgeDeletedBefore(Instant threshold);
    // 채팅 내용 저장
    ChatMessage upsertInbound(
            String id,
            String clientMessageId,
            Long chatRoomId,
            Long memberId,
            String content,
            List<ChatAttachment> attachments,
            List<LoopCreateRequest> recommendations,
            Long loopRuleId,
            String deleteMessageId,
            ChatMessage.AuthorType authorType,
            Instant createdAt,
            Instant modifiedAt
    );

    // 채팅방 내 내용 검색 (커서 기반)
    List<ChatMessage> searchByKeyword(Long chatRoomId, String keyword, Instant cursorCreatedAt, String cursorId, int limit);
}
