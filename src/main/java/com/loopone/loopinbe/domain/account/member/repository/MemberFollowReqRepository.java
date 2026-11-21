package com.loopone.loopinbe.domain.account.member.repository;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollowReq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface MemberFollowReqRepository extends JpaRepository<MemberFollowReq, Long> {

    @Modifying
    @Query(value = "DELETE FROM member_follow_req WHERE deleted_at IS NOT NULL AND deleted_at < :threshold", nativeQuery = true)
    int purgeDeletedBefore(@Param("threshold") Instant threshold);
    // 팔로우 요청 존재여부 확인
    boolean existsByFollowReqAndFollowRec(Member followReq, Member followRec);

    // 팔로우 요청 가져오기
    Optional<MemberFollowReq> findByFollowReqAndFollowRec(Member followReq, Member followRec);
}
