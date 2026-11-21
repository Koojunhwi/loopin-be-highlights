package com.loopone.loopinbe.domain.notification.setting.service;

import com.loopone.loopinbe.domain.notification.setting.dto.req.NotificationSettingUpdateRequest;
import com.loopone.loopinbe.domain.notification.setting.dto.res.NotificationSettingResponse;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.global.security.MemberPrincipal;

import java.util.List;

public interface NotificationSettingService {

    void updateSetting(NotificationSettingUpdateRequest request, MemberPrincipal currentUser);

    NotificationSettingResponse getSettings(MemberPrincipal currentUser);

    boolean isEnabled(Long memberId, NotificationSettingType type);

    List<Long> filterEnabled(List<Long> memberIds, NotificationSettingType type);
}
