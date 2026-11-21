package com.loopone.loopinbe.domain.notification.notification.repository;

import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    @Query(value = "{'receiverId': ?0}", sort = "{'createdAt': -1, '_id': -1}")
    List<Notification> findByReceiverIdFirstPage(Long receiverId, Pageable pageable);

    @Query(value = "{'receiverId': ?0, '$or': [{'createdAt': {'$lt': ?1}}, {'createdAt': ?1, '_id': {'$lt': ?2}}]}", sort = "{'createdAt': -1, '_id': -1}")
    List<Notification> findByReceiverIdWithCursor(Long receiverId, Instant cursorCreatedAt, String cursorId, Pageable pageable);

    List<Notification> findAllByIdInAndReceiverId(List<String> ids, Long receiverId);

    List<Notification> findAllByObjectIdAndReceiverId(Long objectId, Long receiverId);
}
