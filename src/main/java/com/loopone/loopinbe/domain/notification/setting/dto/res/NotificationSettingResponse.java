package com.loopone.loopinbe.domain.notification.setting.dto.res;

import com.loopone.loopinbe.domain.notification.setting.entity.MemberNotificationSetting;

public record NotificationSettingResponse(boolean teamChatEnabled, boolean teamInviteEnabled) {

    public static NotificationSettingResponse of(MemberNotificationSetting setting) {
        return new NotificationSettingResponse(
                setting.isTeamChatEnabled(),
                setting.isTeamInviteEnabled()
        );
    }

    public static NotificationSettingResponse defaultOn() {
        return new NotificationSettingResponse(true, true);
    }
}
