package com.loopone.loopinbe.domain.notification.setting.serviceImpl;

import com.loopone.loopinbe.domain.notification.setting.dto.req.NotificationSettingUpdateRequest;
import com.loopone.loopinbe.domain.notification.setting.dto.res.NotificationSettingResponse;
import com.loopone.loopinbe.domain.notification.setting.entity.MemberNotificationSetting;
import com.loopone.loopinbe.domain.notification.setting.entity.NotificationSettingType;
import com.loopone.loopinbe.domain.notification.setting.repository.MemberNotificationSettingRepository;
import com.loopone.loopinbe.domain.notification.setting.service.NotificationSettingService;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationSettingServiceImpl implements NotificationSettingService {

    private final MemberNotificationSettingRepository memberNotificationSettingRepository;

    // 알림 권한 설정 변경 (설정 행이 없으면 기본값에서 생성 후 upsert)
    @Override
    @Transactional
    public void updateSetting(NotificationSettingUpdateRequest request, MemberPrincipal currentUser) {
        MemberNotificationSetting setting = memberNotificationSettingRepository
                .findByMemberId(currentUser.getId())
                .orElseGet(() -> MemberNotificationSetting.createDefault(currentUser.getId()));
        setting.updateSetting(request.type(), request.enabled());
        memberNotificationSettingRepository.save(setting);
    }

    // 알림 권한 설정 조회 (설정 행이 없으면 기본 ON)
    @Override
    @Transactional(readOnly = true)
    public NotificationSettingResponse getSettings(MemberPrincipal currentUser) {
        return memberNotificationSettingRepository.findByMemberId(currentUser.getId())
                .map(NotificationSettingResponse::of)
                .orElseGet(NotificationSettingResponse::defaultOn);
    }

    // 단일 수신자의 특정 알림 활성 여부 (설정 행이 없으면 기본 ON)
    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(Long memberId, NotificationSettingType type) {
        return memberNotificationSettingRepository.findByMemberId(memberId)
                .map(setting -> setting.isEnabled(type))
                .orElse(true);
    }

    // 여러 수신자 중 특정 알림이 활성인 수신자만 (입력 순서 유지) 반환. 설정 행 없으면 기본 ON이라 포함.
    @Override
    @Transactional(readOnly = true)
    public List<Long> filterEnabled(List<Long> memberIds, NotificationSettingType type) {
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        Map<Long, MemberNotificationSetting> settingByMember = memberNotificationSettingRepository
                .findByMemberIdIn(memberIds).stream()
                .collect(Collectors.toMap(MemberNotificationSetting::getMemberId, Function.identity()));
        return memberIds.stream()
                .filter(id -> {
                    MemberNotificationSetting setting = settingByMember.get(id);
                    return setting == null || setting.isEnabled(type);
                })
                .toList();
    }
}
