package com.loopone.loopinbe.domain.account.member.serviceImpl;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.dto.req.MemberCreateRequest;
import com.loopone.loopinbe.domain.account.member.dto.req.MemberUpdateRequest;
import com.loopone.loopinbe.domain.account.member.dto.res.DetailMemberResponse;
import com.loopone.loopinbe.domain.account.member.dto.res.MemberResponse;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollow;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollowReq;
import com.loopone.loopinbe.domain.account.member.entity.MemberPage;
import com.loopone.loopinbe.domain.account.member.mapper.MemberMapper;
import com.loopone.loopinbe.domain.account.member.enums.ProfileImageState;
import com.loopone.loopinbe.domain.account.member.repository.MemberFollowRepository;
import com.loopone.loopinbe.domain.account.member.repository.MemberFollowReqRepository;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.domain.account.member.service.MemberService;
import com.loopone.loopinbe.domain.account.auth.service.AuthService;
import com.loopone.loopinbe.domain.chat.chatMessage.dto.ChatAttachment;
import com.loopone.loopinbe.domain.chat.chatRoom.service.ChatRoomService;
import com.loopone.loopinbe.domain.loop.loop.service.LoopService;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.team.team.service.TeamInvitationService;
import com.loopone.loopinbe.domain.team.team.service.TeamJoinRequestService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import com.loopone.loopinbe.domain.notification.notification.factory.NotificationPayloadFactory;
import com.loopone.loopinbe.domain.team.team.service.TeamService;
import com.loopone.loopinbe.global.common.cursor.CursorUtils;
import com.loopone.loopinbe.global.common.response.PageResponse;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.global.exception.ReturnCode;
import com.loopone.loopinbe.global.exception.ServiceException;
import com.loopone.loopinbe.global.kafka.event.notification.NotificationEventPublisher;
import com.loopone.loopinbe.global.s3.S3Service;
import org.springframework.context.annotation.Lazy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static com.loopone.loopinbe.global.constants.KafkaKey.FOLLOW_NOTIFICATION_TOPIC;
import static org.springframework.util.StringUtils.hasText;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {
    private final MemberRepository memberRepository;
    private final MemberFollowReqRepository memberFollowReqRepository;
    private final MemberFollowRepository memberFollowRepository;
    private final S3Service s3Service;
    private final MemberMapper memberMapper;
    private final ChatRoomService chatRoomService;
    private final TeamService teamService;
    private final LoopService loopService;
    private final TeamInvitationService teamInvitationService;
    private final TeamJoinRequestService teamJoinRequestService;
    private final CacheManager cacheManager;
    private final NotificationEventPublisher notificationEventPublisher;
    private final EncryptionProperties encryptionProperties;

    private AuthService authService;

    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthService(AuthService authService) {
        this.authService = authService;
    }

    // 회원가입
    @Override
    @Transactional
    public Member regularSignUp(MemberCreateRequest memberCreateRequest) {
        String emailBlindIndex = encryptionProperties.generateBlindIndex(memberCreateRequest.getEmail());
        // 계정 식별 기준은 소셜 계정이다. 같은 이메일이라도 provider가 다르면 별도 계정으로 허용한다
        if (memberCreateRequest.getProvider() != null && memberCreateRequest.getProviderId() != null
                && memberRepository.existsBySocialAccount(
                        memberCreateRequest.getProvider(), memberCreateRequest.getProviderId())) {
            throw new ServiceException(ReturnCode.SOCIAL_ACCOUNT_ALREADY_IN_USE);
        }
        if (memberRepository.existsByNickname(memberCreateRequest.getNickname())) {
            throw new ServiceException(ReturnCode.NICKNAME_ALREADY_USED);
        }
        // BANNED 이력이 있는 소셜 계정은 재가입 차단 (제재 우회 방지)
        if (memberCreateRequest.getProvider() != null && memberCreateRequest.getProviderId() != null
                && memberRepository.existsBannedByProviderAndProviderId(
                        memberCreateRequest.getProvider().name(), memberCreateRequest.getProviderId())) {
            throw new ServiceException(ReturnCode.BANNED_MEMBER_SIGNUP);
        }
        Member member = Member.builder()
                .email(memberCreateRequest.getEmail())
                .emailBlindIndex(emailBlindIndex)
//                .password(encodedPassword)
                .nickname(memberCreateRequest.getNickname())
//                .phone(memberCreateRequest.getPhone())// 인코딩된 비밀번호 저장
//                .gender(memberCreateRequest.getGender())
//                .birthday(memberCreateRequest.getBirthday())
                .oAuthProvider(memberCreateRequest.getProvider())
                .providerId(memberCreateRequest.getProviderId())
                .build();
        memberRepository.save(member);
        return member;
    }

    // 본인 회원정보 조회
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "myInfo", key = "#currentUser.getId()")
    public MemberResponse getMyInfo(MemberPrincipal currentUser) {
        Member member = memberRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        return memberMapper.toMemberResponse(member);
    }

    // 본인 상세회원정보 조회
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "myDetailInfo", key = "#currentUser.getId()")
    public DetailMemberResponse getMyDetailInfo(MemberPrincipal currentUser){
        Member member = memberRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        return memberMapper.toDetailMemberResponse(member);
    }

    // 다른 멤버의 회원정보 조회
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "memberInfo", key = "#memberId")
    public MemberResponse getMemberInfo(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        return memberMapper.toMemberResponse(member);
    }

    // 다른 멤버의 상세회원정보 조회
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "memberDetail", key = "#memberId")
    public DetailMemberResponse getMemberDetailInfo(Long memberId){
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        return memberMapper.toDetailMemberResponse(member);
    }

    // 닉네임 중복 확인
    @Override
    @Transactional(readOnly = true)
    public void checkNickname(String nickname) {
        if (memberRepository.existsByNickname(nickname)) {
            throw new ServiceException(ReturnCode.NICKNAME_ALREADY_USED);
        }
    }

    // 회원정보 수정
    @Override
    @Transactional
    public void updateMember(MemberUpdateRequest memberUpdateRequest, MultipartFile imageFile, MemberPrincipal currentUser) {
        Member member = memberRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        if (memberRepository.existsByNickname(memberUpdateRequest.nickname())) {
            throw new ServiceException(ReturnCode.NICKNAME_ALREADY_USED);
        }
        ProfileImageState state = (memberUpdateRequest.profileImageState() != null)
                ? memberUpdateRequest.profileImageState() : ProfileImageState.MAINTAIN;
        String currentImageUrl = currentUser.getProfileImageUrl(); // 기본적으로 기존 이미지 URL을 사용
        String finalImageUrl = currentImageUrl;
        switch (state) {
            case MAINTAIN -> {
                // 유지: 아무것도 안 함 (imageFile이 와도 무시/유지)
                finalImageUrl = currentImageUrl;
            }
            case UPDATE -> {
                // 교체: imageFile 필수, 기존 이미지 있으면 삭제 후 새 이미지 업로드
                if (imageFile == null || imageFile.isEmpty()) {
                    throw new ServiceException(ReturnCode.PROFILE_IMAGE_REQUIRED);
                }
                try {
                    // 1) 새 이미지 업로드 (먼저 업로드 성공시키고)
                    ChatAttachment uploaded = s3Service.uploadChatImage(imageFile, "profile-images");
                    String newUrl = s3Service.toPublicUrl(uploaded.key());

                    // 2) 기존 이미지 삭제 (기존 URL이 있으면)
                    if (hasText(currentImageUrl)) {
                        String oldKey = extractKeyFromPublicUrl(currentImageUrl);
                        if (hasText(oldKey)) {
                            s3Service.deleteObjectByKey(oldKey);
                        }
                    }
                    // 3) 최종 URL 반영
                    finalImageUrl = newUrl;
                } catch (IOException e) {
                    throw new ServiceException(ReturnCode.INTERNAL_ERROR);
                }
            }
            case DELETE -> {
                // 삭제: 기존 이미지 있으면 삭제 후 비움
                if (hasText(currentImageUrl)) {
                    String oldKey = extractKeyFromPublicUrl(currentImageUrl);
                    if (hasText(oldKey)) {
                        s3Service.deleteObjectByKey(oldKey);
                    }
                }
                finalImageUrl = "";
            }
        }
        member.update(memberUpdateRequest, finalImageUrl, null);
        // 커밋 이후 캐시 무효화
        evictMyInfoCacheAfterCommit(currentUser.getId());
        evictMemberCachesAfterCommit(currentUser.getId());
    }

    // 회원탈퇴
    @Override
    @Transactional
    public void deleteMember(MemberPrincipal currentUser, String accessToken) {
        // DB에서 회원 조회
        Member member = memberRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        // 연관된 데이터 삭제
        chatRoomService.leaveAllChatRooms(currentUser.getId());
        teamInvitationService.deleteAllInvitationsRelatedToMember(currentUser.getId());
        teamJoinRequestService.deleteAllRequestsRelatedToMember(currentUser.getId());
        teamService.deleteMyTeams(member);
        loopService.deleteMyLoops(currentUser.getId());
        // 회원삭제
        memberRepository.delete(member);
        // 커밋 이후 캐시 무효화 및 인증 정리
        evictMyInfoCacheAfterCommit(currentUser.getId());
        evictMemberCachesAfterCommit(currentUser.getId());
        cleanUpAuthAfterCommit(currentUser, accessToken);
    }

    // 회원 검색하기
    @Override
    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> searchMemberInfo(String cursor, int size, String keyword, MemberPrincipal currentUser) {
        checkPageSize(size);
        String trimmed = (keyword == null) ? null : keyword.trim();

        List<MemberResponse> members;
        if (cursor == null) {
            members = memberRepository.findByKeywordFirstPage(trimmed, currentUser.getId(), PageRequest.of(0, size + 1));
        } else {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            members = memberRepository.findByKeywordWithCursor(
                    trimmed, currentUser.getId(), cursorData.createdAt(), cursorData.idAsLong(), PageRequest.of(0, size + 1));
        }

        boolean hasNext = members.size() > size;
        List<MemberResponse> content = hasNext ? members.subList(0, size) : members;

        String nextCursor = null;
        if (hasNext && !content.isEmpty()) {
            MemberResponse last = content.getLast();
            nextCursor = CursorUtils.encode(last.getCreatedAt(), last.getId());
        }

        return PageResponse.of(content, size, hasNext, nextCursor);
    }

    // 팔로우 요청하기
    @Override
    @Transactional
    public void followReq(Long memberId, MemberPrincipal currentUser){
        if (Objects.equals(memberId, currentUser.getId())) {
            throw new ServiceException(ReturnCode.CANNOT_FOLLOW_SELF);
        }
        Member followReq = memberMapper.toMember(currentUser);
        Member followRec = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        // 기존 팔로우 여부 확인
        boolean already_follow = memberFollowRepository.existsByFollowAndFollowed(followReq, followRec);
        if (already_follow) {
            throw new ServiceException(ReturnCode.ALREADY_FOLLOW);
        }
        // 중복 요청 방지
        boolean already_requested = memberFollowReqRepository.existsByFollowReqAndFollowRec(followReq, followRec);
        if (already_requested) {
            throw new ServiceException(ReturnCode.ALREADY_REQUESTED);
        }
        MemberFollowReq memberFollowReq = MemberFollowReq.builder()
                .followReq(followReq)
                .followRec(followRec)
                .build();
        memberFollowReqRepository.save(memberFollowReq);
        // 커밋 이후 캐시 무효화 (양쪽 사용자)
        evictDetailCachesAfterCommit(currentUser.getId(), memberId);
        NotificationPayload payload = NotificationPayloadFactory.memberFollowRequest(followReq, followRec, memberFollowReq);

        // 커밋 이후에만 발행 (롤백 시 이벤트 발행 방지)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationEventPublisher.publishNotification(payload, FOLLOW_NOTIFICATION_TOPIC);
            }
        });
    }

    // 팔로우 요청 취소하기
    @Override
    @Transactional
    public void cancelFollowReq(Long memberId, MemberPrincipal currentUser){
        Member followReq = memberMapper.toMember(currentUser);
        Member followRec = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        MemberFollowReq memberFollowReq = memberFollowReqRepository.findByFollowReqAndFollowRec(followReq, followRec)
                .orElseThrow(() -> new ServiceException(ReturnCode.REQUEST_NOT_FOUND));
        memberFollowReqRepository.delete(memberFollowReq);
        // 커밋 이후 캐시 무효화 (양쪽 사용자)
        evictDetailCachesAfterCommit(currentUser.getId(), memberId);
    }

    // 팔로우 요청 수락하기
    @Override
    @Transactional
    public void acceptFollowReq(Long memberId, MemberPrincipal currentUser){
        Member requester = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        Member receiver = memberMapper.toMember(currentUser);
        MemberFollowReq followReq = memberFollowReqRepository.findByFollowReqAndFollowRec(requester, receiver)
                .orElseThrow(() -> new ServiceException(ReturnCode.REQUEST_NOT_FOUND));
        memberFollowReqRepository.delete(followReq);
        MemberFollow memberFollow = MemberFollow.builder()
                .follow(requester)
                .followed(receiver)
                .build();
        memberFollowRepository.save(memberFollow);
        // 커밋 이후 캐시 무효화 (양쪽 사용자)
        evictDetailCachesAfterCommit(currentUser.getId(), memberId);
        NotificationPayload payload = NotificationPayloadFactory.memberFollowAccepted(receiver, requester, memberFollow);

        // 커밋 이후에만 발행 (롤백 시 이벤트 발행 방지)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationEventPublisher.publishNotification(payload, FOLLOW_NOTIFICATION_TOPIC);
            }
        });
    }

    // 팔로우 요청 거절하기
    @Override
    @Transactional
    public void refuseFollowReq(Long memberId, MemberPrincipal currentUser){
        Member requester = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        Member receiver = memberMapper.toMember(currentUser);
        MemberFollowReq memberFollowReq = memberFollowReqRepository.findByFollowReqAndFollowRec(requester, receiver)
                .orElseThrow(() -> new ServiceException(ReturnCode.REQUEST_NOT_FOUND));
        memberFollowReqRepository.delete(memberFollowReq);
        // 커밋 이후 캐시 무효화 (양쪽 사용자)
        evictDetailCachesAfterCommit(currentUser.getId(), memberId);
    }

    // 팔로우 취소하기
    @Override
    @Transactional
    public void cancelFollow(Long memberId, MemberPrincipal currentUser){
        Member follow = memberMapper.toMember(currentUser);
        Member followed = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        MemberFollow memberFollow = memberFollowRepository.findByFollowAndFollowed(follow, followed)
                .orElseThrow(() -> new ServiceException(ReturnCode.FOLLOW_NOT_FOUND));
        memberFollowRepository.delete(memberFollow);
        // 커밋 이후 캐시 무효화 (양쪽 사용자)
        evictDetailCachesAfterCommit(currentUser.getId(), memberId);
    }

    // 팔로워 목록에서 해당 유저 삭제하기
    @Override
    @Transactional
    public void removeFollowed(Long memberId, MemberPrincipal currentUser){
        Member follow = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(ReturnCode.USER_NOT_FOUND));
        Member followed = memberMapper.toMember(currentUser);
        MemberFollow memberFollow = memberFollowRepository.findByFollowAndFollowed(follow, followed)
                .orElseThrow(() -> new ServiceException(ReturnCode.FOLLOWER_NOT_FOUND));
        memberFollowRepository.delete(memberFollow);
        // 커밋 이후 캐시 무효화 (양쪽 사용자)
        evictDetailCachesAfterCommit(currentUser.getId(), memberId);
    }

    // ----------------- 헬퍼 메서드 -----------------

    // 회원 탈퇴 시 커밋 이후 인증 정리 (AuthService에 위임)
    private void cleanUpAuthAfterCommit(MemberPrincipal currentUser, String accessToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            authService.cleanUpOnWithdraw(currentUser, accessToken);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                authService.cleanUpOnWithdraw(currentUser, accessToken);
            }
        });
    }

    private void checkPageSize(int size) {
        if (size > MemberPage.getMaxPageSize()) {
            throw new ServiceException(ReturnCode.PAGE_REQUEST_FAIL);
        }
    }

    private String extractKeyFromPublicUrl(String publicUrl) {
        try {
            java.net.URI uri = java.net.URI.create(publicUrl);
            String path = uri.getPath(); // "/profile-images/...."
            if (path == null) return null;
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (IllegalArgumentException e) {
            // URL 형태가 깨져있으면 내부오류로 보는 게 맞음(가정 위반)
            throw new ServiceException(ReturnCode.INTERNAL_ERROR);
        }
    }

    // 트랜잭션 커밋 이후에 캐시 무효화 (롤백 시 캐시 유지)
    private void evictMyInfoCacheAfterCommit(Long memberId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션이 없으면 즉시 evict
            evictMyInfoCache(memberId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictMyInfoCache(memberId);
            }
        });
    }

    // 캐시 무효화
    private void evictMyInfoCache(Long memberId) {
        Cache cache = cacheManager.getCache("myInfo");
        if (cache != null) {
            cache.evictIfPresent(memberId);
            log.debug("MyInfo cache evicted: {}", memberId);
        }
    }

    // 트랜잭션 커밋 이후에 member 관련 캐시 무효화 (myDetailInfo, memberDetail, memberInfo)
    private void evictMemberCachesAfterCommit(Long memberId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictMemberCaches(memberId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictMemberCaches(memberId);
            }
        });
    }

    // myDetailInfo, memberDetail, memberInfo 캐시 무효화
    private void evictMemberCaches(Long memberId) {
        evictCache("myDetailInfo", memberId);
        evictCache("memberDetail", memberId);
        evictCache("memberInfo", memberId);
    }

    // 트랜잭션 커밋 이후에 detail 관련 캐시 무효화 (양쪽 사용자)
    private void evictDetailCachesAfterCommit(Long myId, Long targetId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictDetailCaches(myId, targetId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictDetailCaches(myId, targetId);
            }
        });
    }

    // myDetailInfo, memberDetail 캐시 무효화 (양쪽 사용자)
    private void evictDetailCaches(Long myId, Long targetId) {
        evictCache("myDetailInfo", myId);
        evictCache("myDetailInfo", targetId);
        evictCache("memberDetail", myId);
        evictCache("memberDetail", targetId);
    }

    // 단일 캐시 evict 헬퍼
    private void evictCache(String cacheName, Long key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evictIfPresent(key);
            log.debug("{} cache evicted: {}", cacheName, key);
        }
    }
}
