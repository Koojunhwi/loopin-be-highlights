package com.loopone.loopinbe.domain.chat.chatMessage.repositoryImpl;

import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatAttachment;
import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import com.loopone.loopinbe.domain.chat.chatMessage.repository.ChatMessageMongoRepositoryCustom;
import com.loopone.loopinbe.domain.loop.loop.dto.req.LoopCreateRequest;
import lombok.RequiredArgsConstructor;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class ChatMessageMongoRepositoryImpl implements ChatMessageMongoRepositoryCustom {
    private final MongoTemplate mongoTemplate;

    // 채팅 내용 저장
    @Override
    public ChatMessage upsertInbound(
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
    ) {
        Query q = new Query(Criteria.where("_id").is(id));
        Update u = new Update()
                // 멱등: 최초 삽입시에만 고정되는 필드
                .setOnInsert("_id", id)
                .setOnInsert("clientMessageId", clientMessageId)
                .setOnInsert("chatRoomId", chatRoomId)
                .setOnInsert("memberId", memberId)
                .setOnInsert("content", content)
                .setOnInsert("attachments", attachments)
                .setOnInsert("recommendations", recommendations)
                .setOnInsert("loopRuleId", loopRuleId)
                .setOnInsert("deleteMessageId", deleteMessageId)
                .setOnInsert("authorType", authorType)
                .setOnInsert("createdAt", createdAt)
                .setOnInsert("modifiedAt", modifiedAt);
        FindAndModifyOptions opt = FindAndModifyOptions.options()
                .upsert(true)
                .returnNew(true);
        return mongoTemplate.findAndModify(q, u, opt, ChatMessage.class);
    }

    @Override
    public long softDeleteByChatRoomId(Long chatRoomId) {
        Query query = new Query(Criteria.where("chatRoomId").is(chatRoomId).and("deletedAt").isNull());
        Update update = new Update().set("deletedAt", Instant.now());
        UpdateResult result = mongoTemplate.updateMulti(query, update, ChatMessage.class);
        return result.getModifiedCount();
    }

    @Override
    public long purgeDeletedBefore(Instant threshold) {
        Query query = new Query(Criteria.where("deletedAt").ne(null).lt(threshold));
        DeleteResult result = mongoTemplate.remove(query, ChatMessage.class);
        return result.getDeletedCount();
    }

    // 채팅방 내 내용 검색 (커서 기반)
    @Override
    public List<ChatMessage> searchByKeyword(Long chatRoomId, String keyword, Instant cursorCreatedAt, String cursorId, int limit) {
        if (chatRoomId == null) throw new IllegalArgumentException("chatRoomId is null");
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        // keyword의 "문자 하나라도 포함" -> (문자1|문자2|...) OR 정규식
        List<String> chars = new ArrayList<>();
        keyword.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));

        String alternation = chars.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow();

        Pattern pattern = Pattern.compile(alternation);

        Criteria criteria = Criteria.where("chatRoomId").is(chatRoomId)
                .and("deletedAt").isNull()
                .and("content").regex(pattern);

        // 커서 조건 추가
        if (cursorCreatedAt != null && cursorId != null) {
            criteria = criteria.andOperator(
                    new Criteria().orOperator(
                            Criteria.where("createdAt").lt(cursorCreatedAt),
                            Criteria.where("createdAt").is(cursorCreatedAt).and("_id").lt(cursorId)
                    )
            );
        }

        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(limit);

        return mongoTemplate.find(query, ChatMessage.class);
    }
}
