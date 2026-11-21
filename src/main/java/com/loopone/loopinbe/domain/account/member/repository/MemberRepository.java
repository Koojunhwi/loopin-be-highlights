package com.loopone.loopinbe.domain.account.member.repository;

import com.loopone.loopinbe.domain.account.member.dto.res.MemberResponse;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    @Modifying
    @Query(value = "DELETE FROM member WHERE deleted_at IS NOT NULL AND deleted_at < :threshold", nativeQuery = true)
    int purgeDeletedBefore(@Param("threshold") Instant threshold);

    // 고유한 닉네임인지 확인
    boolean existsByNickname(String nickname);

    // 소셜 계정(provider + providerId)으로 회원 조회 — 계정 식별의 기준
    @Query("select m from Member m where m.oAuthProvider = :provider and m.providerId = :providerId")
    Optional<Member> findBySocialAccount(@Param("provider") Member.OAuthProvider provider,
                                         @Param("providerId") String providerId);

    // 소셜 계정 중복 확인 (가입 시)
    @Query("select count(m) > 0 from Member m where m.oAuthProvider = :provider and m.providerId = :providerId")
    boolean existsBySocialAccount(@Param("provider") Member.OAuthProvider provider,
                                  @Param("providerId") String providerId);

    // 같은 소셜 계정의 BANNED 이력 존재 여부 (native: soft delete된 행 포함 — 탈퇴 후 재가입으로 제재를 우회하지 못하게 막는다)
    @Query(value = """
            SELECT EXISTS(
                SELECT 1 FROM member
                WHERE state = 'BANNED'
                  AND o_auth_provider = :provider
                  AND provider_id = :providerId
            )
            """, nativeQuery = true)
    boolean existsBannedByProviderAndProviderId(@Param("provider") String provider, @Param("providerId") String providerId);

    // blind index로 회원 조회 (개발용 이메일 로그인)
    @EntityGraph(value = "Member.withAllRelations", type = EntityGraph.EntityGraphType.LOAD)
    Optional<Member> findByEmailBlindIndex(String emailBlindIndex);

    // 회원 검색 (첫 페이지)
    @Query("""
            SELECT new com.loopone.loopinbe.domain.account.member.dto.res.MemberResponse(
                m.id, m.email, m.nickname, m.profileImageUrl, m.createdAt
            )
            FROM Member m
            WHERE m.id <> :currentUserId
              AND (:keyword IS NULL OR :keyword = '' OR LOWER(m.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY m.createdAt DESC, m.id DESC
        """)
    List<MemberResponse> findByKeywordFirstPage(
            @Param("keyword") String keyword,
            @Param("currentUserId") Long currentUserId,
            Pageable pageable
    );

    // 회원 검색 (커서 기반)
    @Query("""
            SELECT new com.loopone.loopinbe.domain.account.member.dto.res.MemberResponse(
                m.id, m.email, m.nickname, m.profileImageUrl, m.createdAt
            )
            FROM Member m
            WHERE m.id <> :currentUserId
              AND (:keyword IS NULL OR :keyword = '' OR LOWER(m.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (m.createdAt < :cursorCreatedAt OR (m.createdAt = :cursorCreatedAt AND m.id < :cursorId))
            ORDER BY m.createdAt DESC, m.id DESC
        """)
    List<MemberResponse> findByKeywordWithCursor(
            @Param("keyword") String keyword,
            @Param("currentUserId") Long currentUserId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    List<Member> findAllByNicknameIn(List<String> invitedNicknames);
}
