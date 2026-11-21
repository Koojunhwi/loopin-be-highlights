package com.loopone.loopinbe.domain.chat.chatMessage.entity;

import lombok.Data;
import lombok.Getter;

@Data
public class ChatMessagePage {
    private String cursor;
    private int size = 50;
    @Getter
    private static final int maxPageSize = 50;
}
