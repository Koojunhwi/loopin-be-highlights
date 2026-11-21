package com.loopone.loopinbe.domain.chat.chatRoom.service;

import com.loopone.loopinbe.domain.account.member.entity.Member;
import com.loopone.loopinbe.domain.account.member.repository.MemberRepository;
import com.loopone.loopinbe.domain.chat.chatRoom.entity.ChatRoom;
import com.loopone.loopinbe.domain.chat.chatRoom.entity.ChatRoomMember;
import com.loopone.loopinbe.domain.chat.chatRoom.repository.ChatRoomMemberRepository;
import com.loopone.loopinbe.domain.chat.chatRoom.repository.ChatRoomRepository;
import com.loopone.loopinbe.domain.chat.chatRoom.serviceImpl.ChatRoomMemberServiceImpl;
import com.loopone.loopinbe.global.encryption.EmailLocalPartEncryptor;
import com.loopone.loopinbe.global.encryption.EncryptionProperties;
import com.loopone.loopinbe.support.TestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({ TestContainersConfig.class, EncryptionProperties.class, EmailLocalPartEncryptor.class, ChatRoomMemberServiceImpl.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatRoomMemberServiceOtherMemberIdsTest {

    @Autowired
    ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    ChatRoomRepository chatRoomRepository;
    @Autowired
    EncryptionProperties encryptionProperties;

    @Autowired
    ChatRoomMemberServiceImpl chatRoomMemberService;

    @AfterEach
    void cleanup() {
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private Member persistMember(String email, String nickname) {
        Member m = Member.builder()
                .email(email)
                .emailBlindIndex(encryptionProperties.generateBlindIndex(email))
                .nickname(nickname)
                .profileImageUrl(null)
                .state(Member.State.NORMAL)
                .role(Member.MemberRole.ROLE_USER)
                .oAuthProvider(Member.OAuthProvider.GOOGLE)
                .providerId("pid-" + email)
                .build();
        return memberRepository.saveAndFlush(m);
    }

    private ChatRoom persistChatRoom(Member owner) {
        ChatRoom room = ChatRoom.builder()
                .member(owner)
                .title("팀방")
                .isBotRoom(false)
                .build();
        return chatRoomRepository.saveAndFlush(room);
    }

    private ChatRoomMember persistChatRoomMember(ChatRoom room, Member member) {
        ChatRoomMember chatRoomMember = ChatRoomMember.builder()
                .member(member)
                .chatRoom(room)
                .build();
        return chatRoomMemberRepository.saveAndFlush(chatRoomMember);
    }

    @Nested
    @DisplayName("getOtherMemberIds")
    class GetOtherMemberIds {

        @Test
        @DisplayName("성공: 발신자를 제외한 방 멤버 id를 반환한다")
        void success() {
            Member owner = persistMember("owner@test.com", "A");
            Member memberB = persistMember("b@test.com", "B");
            Member memberC = persistMember("c@test.com", "C");
            ChatRoom room = persistChatRoom(owner);
            persistChatRoomMember(room, owner);
            persistChatRoomMember(room, memberB);
            persistChatRoomMember(room, memberC);

            var result = chatRoomMemberService.getOtherMemberIds(room.getId(), owner.getId());

            assertThat(result).containsExactlyInAnyOrder(memberB.getId(), memberC.getId());
            assertThat(result).doesNotContain(owner.getId());
        }

        @Test
        @DisplayName("다른 방 멤버는 결과에 포함하지 않는다")
        void excludesMembersFromOtherRoom() {
            Member memberA = persistMember("a@test.com", "A");
            Member memberB = persistMember("b@test.com", "B");
            Member memberC = persistMember("c@test.com", "C");
            Member memberD = persistMember("d@test.com", "D");
            ChatRoom room1 = persistChatRoom(memberA);
            ChatRoom room2 = persistChatRoom(memberC);
            persistChatRoomMember(room1, memberA);
            persistChatRoomMember(room1, memberB);
            persistChatRoomMember(room2, memberC);
            persistChatRoomMember(room2, memberD);

            var result = chatRoomMemberService.getOtherMemberIds(room1.getId(), memberA.getId());

            assertThat(result).containsExactly(memberB.getId());
        }

        @Test
        @DisplayName("발신자가 유일한 멤버면 빈 리스트를 반환한다")
        void returnsEmptyWhenSenderIsOnlyMember() {
            Member memberA = persistMember("a@test.com", "A");
            ChatRoom room = persistChatRoom(memberA);
            persistChatRoomMember(room, memberA);

            var result = chatRoomMemberService.getOtherMemberIds(room.getId(), memberA.getId());

            assertThat(result).isEmpty();
        }
    }
}
