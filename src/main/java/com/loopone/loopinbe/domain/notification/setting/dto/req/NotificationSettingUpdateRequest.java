package com.loopone.loopinbe.domain.notification.setting.dto.req;

import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import jakarta.validation.constraints.NotNull;

public record NotificationSettingUpdateRequest(
        @NotNull(message = "type은 필수입니다.") NotificationSettingType type,
        @NotNull(message = "enabled는 필수입니다.") Boolean enabled
) {
}
