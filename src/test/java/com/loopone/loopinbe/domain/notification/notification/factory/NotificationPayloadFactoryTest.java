package com.loopone.loopinbe.domain.notification.notification.factory;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollow;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollowReq;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.team.team.entity.Team;
import com.loopone.loopinbe.domain.team.team.entity.TeamInvitation;
import com.loopone.loopinbe.domain.team.team.entity.TeamJoinRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationPayloadFactory 단위 테스트")
class NotificationPayloadFactoryTest {

    @Test
    @DisplayName("팀 초대 payload에 저장용 content와 푸시용 pushContent를 구분한다")
    void teamInvite() {
        Member inviter = member(1L, "주니");
        Member invitee = member(2L, "초대받는이");
        Team team = Team.builder().id(10L).name("루프인").leader(inviter).build();
        TeamInvitation invitation = TeamInvitation.builder()
                .id(100L)
                .team(team)
                .inviter(inviter)
                .invitee(invitee)
                .build();

        NotificationPayload payload = NotificationPayloadFactory.teamInvite(invitation);

        assertThat(payload.title()).isEqualTo("팀 초대가 도착했어요");
        assertThat(payload.content()).isEqualTo("주니님이 루프인 팀에 초대했어요");
        assertThat(payload.pushContent()).isEqualTo("주니님이\n루프인 팀에 초대했어요");
        assertThat(payload.targetObject()).isEqualTo(Notification.TargetObject.Invite);
        assertThat(payload.objectId()).isEqualTo(invitation.getId());
        assertThat(payload.senderId()).isEqualTo(inviter.getId());
        assertThat(payload.receiverId()).isEqualTo(invitee.getId());
    }

    @Test
    @DisplayName("팀 참여 요청 payload를 생성한다")
    void teamJoinRequest() {
        Member requester = member(1L, "주니");
        Member leader = member(2L, "리더");
        Team team = Team.builder().id(10L).name("루프인").leader(leader).build();
        TeamJoinRequest joinRequest = TeamJoinRequest.builder()
                .id(101L)
                .team(team)
                .requester(requester)
                .build();

        NotificationPayload payload = NotificationPayloadFactory.teamJoinRequest(joinRequest);

        assertThat(payload.title()).isEqualTo("팀 참여 요청이 도착했어요");
        assertThat(payload.content()).isEqualTo("주니님이 루프인 팀에 참여를 요청했어요");
        assertThat(payload.pushContent()).isEqualTo("주니님이\n루프인 팀에 참여를 요청했어요");
        assertThat(payload.targetObject()).isEqualTo(Notification.TargetObject.Request);
        assertThat(payload.objectId()).isEqualTo(joinRequest.getId());
        assertThat(payload.senderId()).isEqualTo(requester.getId());
        assertThat(payload.receiverId()).isEqualTo(leader.getId());
    }

    @Test
    @DisplayName("팔로우 요청 payload는 pushContent 없이 생성한다")
    void memberFollowRequest() {
        Member requester = member(1L, "주니");
        Member receiver = member(2L, "수신자");
        MemberFollowReq request = MemberFollowReq.builder()
                .id(102L)
                .followReq(requester)
                .followRec(receiver)
                .build();

        NotificationPayload payload = NotificationPayloadFactory.memberFollowRequest(requester, receiver, request);

        assertThat(payload.title()).isEqualTo("새 팔로우 요청이 있어요");
        assertThat(payload.content()).isEqualTo("주니님이 팔로우를 요청하였습니다.");
        assertThat(payload.pushContent()).isNull();
        assertThat(payload.targetObject()).isEqualTo(Notification.TargetObject.Follow);
        assertThat(payload.objectId()).isEqualTo(request.getId());
        assertThat(payload.senderId()).isEqualTo(requester.getId());
        assertThat(payload.receiverId()).isEqualTo(receiver.getId());
    }

    @Test
    @DisplayName("팔로우 수락 payload는 pushContent 없이 생성한다")
    void memberFollowAccepted() {
        Member accepter = member(1L, "주니");
        Member requester = member(2L, "요청자");
        MemberFollow follow = MemberFollow.builder()
                .id(103L)
                .follow(accepter)
                .followed(requester)
                .build();

        NotificationPayload payload = NotificationPayloadFactory.memberFollowAccepted(accepter, requester, follow);

        assertThat(payload.title()).isEqualTo("팔로우 요청이 수락됐어요");
        assertThat(payload.content()).isEqualTo("주니님이 팔로우 요청을 수락하였습니다.");
        assertThat(payload.pushContent()).isNull();
        assertThat(payload.targetObject()).isEqualTo(Notification.TargetObject.Follow);
        assertThat(payload.objectId()).isEqualTo(follow.getId());
        assertThat(payload.senderId()).isEqualTo(accepter.getId());
        assertThat(payload.receiverId()).isEqualTo(requester.getId());
    }

    private Member member(Long id, String nickname) {
        return Member.builder()
                .id(id)
                .nickname(nickname)
                .profileImageUrl("https://example.com/" + id)
                .build();
    }
}
