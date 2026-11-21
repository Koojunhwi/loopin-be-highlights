package com.loopone.loopinbe.domain.notification.notification.factory;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollow;
import com.loopone.loopinbe.domain.account.member.entity.MemberFollowReq;
import com.loopone.loopinbe.domain.notification.notification.dto.NotificationPayload;
import com.loopone.loopinbe.domain.notification.notification.entity.Notification;
import com.loopone.loopinbe.domain.team.team.entity.Team;
import com.loopone.loopinbe.domain.team.team.entity.TeamInvitation;
import com.loopone.loopinbe.domain.team.team.entity.TeamJoinRequest;

public final class NotificationPayloadFactory {

    private NotificationPayloadFactory() {}

    public static NotificationPayload teamInvite(TeamInvitation invitation) {
        Member inviter = invitation.getInviter();
        Member invitee = invitation.getInvitee();
        Team team = invitation.getTeam();
        String senderNickname = inviter.getNickname();
        String senderProfileUrl = extractProfileUrl(inviter); // 프로젝트 필드명에 맞게 조정
        String title = "팀 초대가 도착했어요";
        String content = String.format("%s님이 %s 팀에 초대했어요", senderNickname, team.getName());       // 인앱 본문(공백)
        String pushContent = String.format("%s님이\n%s 팀에 초대했어요", senderNickname, team.getName());   // 푸시 본문(\n)

        return new NotificationPayload(
                inviter.getId(),                 // senderId
                senderNickname,                  // senderNickname
                senderProfileUrl,                // senderProfileUrl
                invitee.getId(),                 // receiverId
                invitation.getId(),              // objectId
                title,                           // title(인앱 헤더)
                content,                         // content(인앱 본문)
                pushContent,                     // pushContent(푸시 본문)
                Notification.TargetObject.Invite // targetObject
        );
    }

    public static NotificationPayload teamJoinRequest(TeamJoinRequest joinRequest) {
        Member requester = joinRequest.getRequester();
        Member leader = joinRequest.getTeam().getLeader();
        Team team = joinRequest.getTeam();
        String senderNickname = requester.getNickname();
        String senderProfileUrl = extractProfileUrl(requester);
        String title = "팀 참여 요청이 도착했어요";
        String content = String.format("%s님이 %s 팀에 참여를 요청했어요", senderNickname, team.getName());       // 인앱 본문(공백)
        String pushContent = String.format("%s님이\n%s 팀에 참여를 요청했어요", senderNickname, team.getName());   // 푸시 본문(\n)

        return new NotificationPayload(
                requester.getId(),
                senderNickname,
                senderProfileUrl,
                leader.getId(),
                joinRequest.getId(),
                title,
                content,
                pushContent,
                Notification.TargetObject.Request
        );
    }

    // 팔로우 요청 알림: requester -> receiver
    public static NotificationPayload memberFollowRequest(Member requester, Member receiver, MemberFollowReq reqEntity) {
        String senderNickname = requester.getNickname();
        String senderProfileUrl = extractProfileUrl(requester);
        String title = "새 팔로우 요청이 있어요";
        String content = String.format("%s님이 팔로우를 요청하였습니다.", senderNickname);

        return new NotificationPayload(
                requester.getId(),                 // senderId
                senderNickname,                    // senderNickname
                senderProfileUrl,                  // senderProfileUrl
                receiver.getId(),                  // receiverId
                reqEntity.getId(),                 // objectId (follow request id)
                title,                             // title(인앱 헤더)
                content,                           // content(인앱=푸시 동일 → pushContent 생략)
                null,                              // pushContent
                Notification.TargetObject.Follow   // targetObject
        );
    }

    // 팔로우 수락 알림: receiver(수락한 사람) -> requester(요청한 사람)
    public static NotificationPayload memberFollowAccepted(Member accepter, Member requester, MemberFollow followEntity) {
        String senderNickname = accepter.getNickname();
        String senderProfileUrl = extractProfileUrl(accepter);
        String title = "팔로우 요청이 수락됐어요";
        String content = String.format("%s님이 팔로우 요청을 수락하였습니다.", senderNickname);

        return new NotificationPayload(
                accepter.getId(),                  // senderId (수락한 사람)
                senderNickname,
                senderProfileUrl,
                requester.getId(),                 // receiverId (요청한 사람)
                followEntity.getId(),              // objectId (follow id)
                title,                             // title(인앱 헤더)
                content,                           // content(인앱=푸시 동일 → pushContent 생략)
                null,                              // pushContent
                Notification.TargetObject.Follow
        );
    }

    private static String extractProfileUrl(Member inviter) {
        try {
            return inviter.getProfileImageUrl();
        } catch (Exception ignored) {
            return null;
        }
    }
}
