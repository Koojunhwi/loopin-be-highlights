package com.loopone.loopinbe.domain.account.member.entity;

import lombok.Data;
import lombok.Getter;

@Data
public class MemberPage {
    private String cursor;
    private int size = 15;
    @Getter
    private static final int maxPageSize = 15;
}
