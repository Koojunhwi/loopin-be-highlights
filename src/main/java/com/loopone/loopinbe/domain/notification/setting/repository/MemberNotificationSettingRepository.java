package com.loopone.loopinbe.domain.notification.setting.repository;

import com.loopone.loopinbe.domain.notification.setting.entity.MemberNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberNotificationSettingRepository extends JpaRepository<MemberNotificationSetting, Long> {

    Optional<MemberNotificationSetting> findByMemberId(Long memberId);

    List<MemberNotificationSetting> findByMemberIdIn(Collection<Long> memberIds);

    // 이 엔티티는 soft delete를 쓰지 않으므로(탈퇴해도 행이 남음) 퍼지 대상 회원의 설정을 member 하드 삭제 전에 지운다
    @Modifying
    @Query(value = """
            DELETE FROM member_notification_setting
            WHERE member_id IN (
                SELECT id FROM member WHERE deleted_at IS NOT NULL AND deleted_at < :threshold
            )
            """, nativeQuery = true)
    int purgeForMembersDeletedBefore(@Param("threshold") Instant threshold);
}
