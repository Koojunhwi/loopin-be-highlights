package com.loopone.loopinbe.domain.notification.setting.service;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.domain.notification.setting.dto.req.NotificationSettingUpdateRequest;
import com.loopone.loopinbe.domain.notification.setting.dto.res.NotificationSettingResponse;
import com.loopone.loopinbe.domain.notification.setting.entity.MemberNotificationSetting;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.domain.notification.setting.repository.MemberNotificationSettingRepository;
import com.loopone.loopinbe.domain.notification.setting.serviceImpl.NotificationSettingServiceImpl;
import com.loopone.loopinbe.global.encryption.EmailLocalPartEncryptor;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.support.TestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({ TestContainersConfig.class, EncryptionProperties.class, EmailLocalPartEncryptor.class, NotificationSettingServiceImpl.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationSettingServiceTest {

    @Autowired
    MemberNotificationSettingRepository memberNotificationSettingRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    EncryptionProperties encryptionProperties;

    @Autowired
    NotificationSettingServiceImpl notificationSettingService;

    @AfterEach
    void cleanup() {
        memberNotificationSettingRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private Member persistMember(String email, String nickname) {
        Member m = Member.builder()
                .email(email)
                .emailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .nickname(nickname)
                .profileImageUrl(null)
                .state(Member.State.NORMAL)
                .role(Member.MemberRole.ROLE_USER)
                .oAuthProvider(Member.OAuthProvider.GOOGLE)
                .providerId("pid-" + email)
                .build();
        return memberRepository.saveAndFlush(m);
    }

    private MemberPrincipal principalOf(Member member) {
        return new MemberPrincipal(
                member.getId(), member.getEmail(), member.getPassword(), member.getNickname(), member.getPhone(),
                member.getGender(), member.getBirthday(), member.getProfileImageUrl(), member.getState(),
                member.getRole(), member.getOAuthProvider(), member.getProviderId()
        );
    }

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("설정 행이 없으면 모든 알림이 켜진 기본값을 반환한다")
        void returnsDefaultOnWhenSettingDoesNotExist() {
            Member member = persistMember("default@test.com", "기본값");
            MemberPrincipal currentUser = principalOf(member);

            NotificationSettingResponse result = notificationSettingService.getSettings(currentUser);

            assertThat(result.teamChatEnabled()).isTrue();
            assertThat(result.teamInviteEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("updateSetting")
    class UpdateSetting {

        @Test
        @DisplayName("설정 행이 없으면 TEAM_CHAT 값과 나머지 기본값으로 새 행을 저장한다")
        void upsertsNewSettingAndGetSettingsReflectsIt() {
            Member member = persistMember("new-setting@test.com", "신규설정");
            MemberPrincipal currentUser = principalOf(member);
            NotificationSettingUpdateRequest request = new NotificationSettingUpdateRequest(
                    NotificationSettingType.TEAM_CHAT,
                    false
            );

            notificationSettingService.updateSetting(request, currentUser);

            MemberNotificationSetting saved = memberNotificationSettingRepository.findByMemberId(member.getId())
                    .orElseThrow();
            assertThat(saved.isTeamChatEnabled()).isFalse();
            assertThat(saved.isTeamInviteEnabled()).isTrue();

            NotificationSettingResponse result = notificationSettingService.getSettings(currentUser);
            assertThat(result.teamChatEnabled()).isFalse();
            assertThat(result.teamInviteEnabled()).isTrue();
        }

        @Test
        @DisplayName("기존 행의 TEAM_INVITE만 바꾸고 TEAM_CHAT 값은 보존한다")
        void updatesExistingSettingAndPreservesOtherValue() {
            Member member = persistMember("existing-setting@test.com", "기존설정");
            MemberNotificationSetting existing = MemberNotificationSetting.createDefault(member.getId());
            existing.updateSetting(NotificationSettingType.TEAM_CHAT, false);
            existing = memberNotificationSettingRepository.saveAndFlush(existing);
            Long existingId = existing.getId();

            notificationSettingService.updateSetting(
                    new NotificationSettingUpdateRequest(NotificationSettingType.TEAM_INVITE, false),
                    principalOf(member)
            );

            MemberNotificationSetting updated = memberNotificationSettingRepository.findByMemberId(member.getId())
                    .orElseThrow();
            assertThat(updated.getId()).isEqualTo(existingId);
            assertThat(updated.isTeamChatEnabled()).isFalse();
            assertThat(updated.isTeamInviteEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("설정 행이 없으면 TEAM_CHAT은 기본으로 활성화된다")
        void returnsTrueWhenSettingDoesNotExist() {
            Member member = persistMember("enabled-default@test.com", "기본활성");

            boolean result = notificationSettingService.isEnabled(
                    member.getId(),
                    NotificationSettingType.TEAM_CHAT
            );

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("TEAM_CHAT을 끄면 비활성화 상태를 반환한다")
        void returnsFalseWhenTeamChatIsDisabled() {
            Member member = persistMember("disabled-chat@test.com", "채팅끔");
            MemberNotificationSetting setting = MemberNotificationSetting.createDefault(member.getId());
            setting.updateSetting(NotificationSettingType.TEAM_CHAT, false);
            memberNotificationSettingRepository.saveAndFlush(setting);

            boolean result = notificationSettingService.isEnabled(
                    member.getId(),
                    NotificationSettingType.TEAM_CHAT
            );

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("filterEnabled")
    class FilterEnabled {

        @Test
        @DisplayName("TEAM_CHAT이 꺼진 회원만 제외하고 설정 행이 없는 회원은 포함한다")
        void excludesDisabledAndIncludesMembersWithoutSetting() {
            Member disabledMember = persistMember("disabled@test.com", "비활성");
            Member enabledMember = persistMember("enabled@test.com", "활성");
            Member defaultMember = persistMember("no-setting@test.com", "설정없음");

            MemberNotificationSetting disabledSetting = MemberNotificationSetting.createDefault(disabledMember.getId());
            disabledSetting.updateSetting(NotificationSettingType.TEAM_CHAT, false);
            memberNotificationSettingRepository.saveAndFlush(disabledSetting);
            memberNotificationSettingRepository.saveAndFlush(
                    MemberNotificationSetting.createDefault(enabledMember.getId())
            );

            List<Long> result = notificationSettingService.filterEnabled(
                    List.of(disabledMember.getId(), enabledMember.getId(), defaultMember.getId()),
                    NotificationSettingType.TEAM_CHAT
            );

            assertThat(result).containsExactlyInAnyOrder(enabledMember.getId(), defaultMember.getId());
        }
    }
}
