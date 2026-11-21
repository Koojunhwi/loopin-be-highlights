package com.loopone.loopinbe.domain.account.member.mapper;

import com.loopone.loopinbe.global.security.MemberPrincipal;
import com.loopone.loopinbe.domain.account.member.dto.res.DetailMemberResponse;
import com.loopone.loopinbe.domain.account.member.dto.res.MemberResponse;
import com.loopone.loopinbe.domain.account.member.entity.Member;
import org.mapstruct.*;

@Mapper(componentModel = "spring", uses = SimpleMemberMapper.class, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemberMapper {
    // ---------- Member -> MemberResponse ----------
    MemberResponse toMemberResponse(Member source);

    // ---------- Member -> DetailMemberResponse ----------
    @Mapping(target = "followMemberCount", expression = "java(source.getFollowList() != null ? (long) source.getFollowList().size() : 0L)")
    @Mapping(target = "followedMemberCount", expression = "java(source.getFollowedList() != null ? (long) source.getFollowedList().size() : 0L)")
    @Mapping(target = "followList", source = "followList", qualifiedByName = "toFollowingMembers")
    @Mapping(target = "followedList", source = "followedList", qualifiedByName = "toFollowerMembers")
    @Mapping(target = "followReqList", source = "followReqList", qualifiedByName = "toSimpleMembersFromFollowReqs")
    @Mapping(target = "followRecList", source = "followRecList", qualifiedByName = "toSimpleMembersFromFollowRecs")
    DetailMemberResponse toDetailMemberResponse(Member source);

    // ---------- MemberPrincipal -> Member ----------
    @Mapping(source = "provider", target = "oAuthProvider")
    Member toMember(MemberPrincipal source);
}
