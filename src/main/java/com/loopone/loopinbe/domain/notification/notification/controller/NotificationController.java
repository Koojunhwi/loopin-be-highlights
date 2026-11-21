package com.loopone.loopinbe.domain.notification.notification.controller;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.notification.notification.dto.req.NotificationRequest;
import com.loopone.loopinbe.domain.notification.notification.dto.res.NotificationResponse;
import com.loopone.loopinbe.domain.notification.notification.entity.NotificationPage;
import com.loopone.loopinbe.domain.notification.notification.service.NotificationService;
import com.loopone.loopinbe.global.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rest-api/v1/notification")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "알림 API")
public class NotificationController {
    private final NotificationService notificationService;

    // 알림 목록 조회
    @GetMapping
    @Operation(summary = "알림 목록 조회", description = "알림 목록을 조회합니다.(기본설정: size=20, cursor=null)")
    public ApiResponse<List<NotificationResponse>> getNotifications(@ModelAttribute NotificationPage request, @AuthenticationPrincipal MemberPrincipal currentUser) {
        return ApiResponse.success(notificationService.getNotifications(request.getCursor(), request.getSize(), currentUser));
    }

    // 알림 읽음 처리
    @PatchMapping
    @Operation(summary = "알림 읽음 처리", description = "알림을 읽음 처리합니다.")
    public ApiResponse<Void> markAsRead(@RequestBody @Valid NotificationRequest notificationRequest,
                                          @AuthenticationPrincipal MemberPrincipal currentUser) {
        notificationService.markAsRead(notificationRequest, currentUser);
        return ApiResponse.success();
    }
}
