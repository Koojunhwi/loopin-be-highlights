package com.loopone.loopinbe.domain.loop.loopReport.service;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.loop.loopReport.dto.res.LoopReportResponse;

public interface LoopReportService {
    // 루프 리포트 조회
    LoopReportResponse getLoopReport(MemberPrincipal currentUser);
}
