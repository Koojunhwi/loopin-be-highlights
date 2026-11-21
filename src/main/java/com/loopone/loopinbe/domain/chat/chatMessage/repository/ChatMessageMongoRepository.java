package com.loopone.loopinbe.domain.chat.chatMessage.repository;

import com.loopone.loopinbe.domain.chat.chatMessage.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ChatMessageMongoRepository extends MongoRepository<ChatMessage, String>,  ChatMessageMongoRepositoryCustom{

    @Query(value = "{'chatRoomId': ?0, 'deletedAt': null}", sort = "{'createdAt': -1, '_id': -1}")
    List<ChatMessage> findByChatRoomIdFirstPage(Long chatRoomId, Pageable pageable);

    @Query(value = "{'chatRoomId': ?0, 'deletedAt': null, '$or': [{'createdAt': {'$lt': ?1}}, {'createdAt': ?1, '_id': {'$lt': ?2}}]}", sort = "{'createdAt': -1, '_id': -1}")
    List<ChatMessage> findByChatRoomIdWithCursor(Long chatRoomId, Instant cursorCreatedAt, String cursorId, Pageable pageable);
}
