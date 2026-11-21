package com.loopone.loopinbe.domain.notification.setting.entity;

import com.loopone.loopinbe.global.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "member_notification_setting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MemberNotificationSetting extends BaseEntity {

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "team_chat_enabled", nullable = false)
    @Builder.Default
    private boolean teamChatEnabled = true;

    @Column(name = "team_invite_enabled", nullable = false)
    @Builder.Default
    private boolean teamInviteEnabled = true;

    public static MemberNotificationSetting createDefault(Long memberId) {
        return MemberNotificationSetting.builder()
                .memberId(memberId)
                .teamChatEnabled(true)
                .teamInviteEnabled(true)
                .build();
    }

    public boolean isEnabled(NotificationSettingType type) {
        return switch (type) {
            case TEAM_CHAT -> teamChatEnabled;
            case TEAM_INVITE -> teamInviteEnabled;
        };
    }

    public void updateSetting(NotificationSettingType type, boolean enabled) {
        switch (type) {
            case TEAM_CHAT -> teamChatEnabled = enabled;
            case TEAM_INVITE -> teamInviteEnabled = enabled;
        }
    }
}
