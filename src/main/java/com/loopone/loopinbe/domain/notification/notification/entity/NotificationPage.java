package com.loopone.loopinbe.domain.notification.notification.entity;

import lombok.Data;
import lombok.Getter;

@Data
public class NotificationPage {
    private String cursor;
    private int size = 20;
    @Getter
    private static final int maxPageSize = 20;
}
