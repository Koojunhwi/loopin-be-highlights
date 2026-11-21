package com.loopone.loopinbe.domain.notification.notification.entity;

import com.loopone.loopinbe.global.persistence.BaseDocument;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "notifications")
@CompoundIndex(name = "idx_receiver_created", def = "{'receiverId': 1, 'createdAt': -1}")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class Notification extends BaseDocument {
    private Long senderId;
    private String senderNickname;
    private String senderProfileUrl;
    private Long receiverId;
    private Long objectId;
    private String title;
    private String content;

    @Builder.Default
    private Boolean isRead = false;

    private TargetObject targetObject;

    @Indexed(name = "idx_ttl_expire", expireAfter = "90d")
    private Instant expireAt;

    public enum TargetObject {
        Follow,
        Invite,
        Request,
        Chat
    }
}
