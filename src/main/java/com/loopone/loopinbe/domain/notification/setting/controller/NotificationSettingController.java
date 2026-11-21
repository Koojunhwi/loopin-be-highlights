package com.loopone.loopinbe.domain.notification.setting.controller;

import com.loopone.loopinbe.domain.notification.setting.dto.req.NotificationSettingUpdateRequest;
import com.loopone.loopinbe.domain.notification.setting.dto.res.NotificationSettingResponse;
import com.loopone.loopinbe.domain.notification.setting.service.NotificationSettingService;
import com.loopone.loopinbe.global.common.response.ApiResponse;
import com.loopone.loopinbe.global.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rest-api/v1/notification/settings")
@RequiredArgsConstructor
@Tag(name = "Notification Setting", description = "알림 권한 설정 API")
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @GetMapping
    @Operation(summary = "알림 권한 설정 조회", description = "팀 채팅 및 팀 초대 알림 수신 설정을 조회합니다.")
    public ApiResponse<NotificationSettingResponse> getSettings(
            @AuthenticationPrincipal MemberPrincipal currentUser
    ) {
        return ApiResponse.success(notificationSettingService.getSettings(currentUser));
    }

    @PatchMapping
    @Operation(summary = "알림 권한 설정 변경", description = "팀 채팅 또는 팀 초대 알림 수신 설정을 변경합니다.")
    public ApiResponse<Void> updateSetting(
            @RequestBody @Valid NotificationSettingUpdateRequest request,
            @AuthenticationPrincipal MemberPrincipal currentUser
    ) {
        notificationSettingService.updateSetting(request, currentUser);
        return ApiResponse.success();
    }
}
