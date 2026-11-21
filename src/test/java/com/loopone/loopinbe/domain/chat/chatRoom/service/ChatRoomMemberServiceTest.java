package com.loopone.loopinbe.domain.chat.chatRoom.service;

import com.loopone.loopinbe.domain.chat.chatRoom.entity.ChatRoomMember;
import com.loopone.loopinbe.domain.chat.chatRoom.repository.ChatRoomMemberRepository;
import com.loopone.loopinbe.domain.chat.chatRoom.serviceImpl.ChatRoomMemberServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(SpringExtension.class)
public class ChatRoomMemberServiceTest {

    @Mock
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @InjectMocks
    private ChatRoomMemberServiceImpl chatRoomMemberService;

    // ==================== updateLastReadAt ====================
    @Nested
    @DisplayName("updateLastReadAt")
    class UpdateLastReadAt {

        @Test
        @DisplayName("성공 - lastReadAt 업데이트 후 최종 값 반환")
        void success() {
            // given
            Long chatRoomId = 10L;
            Long memberId = 1L;
            Instant lastReadAt = Instant.now();
            ChatRoomMember member = ChatRoomMember.builder()
                    .lastReadAt(lastReadAt)
                    .build();

            given(chatRoomMemberRepository.existsByChatRoom_IdAndMember_Id(chatRoomId, memberId)).willReturn(true);
            given(chatRoomMemberRepository.updateLastReadAtIfGreater(chatRoomId, memberId, lastReadAt)).willReturn(1);
            given(chatRoomMemberRepository.findByChatRoom_IdAndMember_Id(chatRoomId, memberId))
                    .willReturn(Optional.of(member));

            // when
            Instant result = chatRoomMemberService.updateLastReadAt(chatRoomId, memberId, lastReadAt);

            // then
            assertThat(result).isEqualTo(lastReadAt);
            then(chatRoomMemberRepository).should().updateLastReadAtIfGreater(chatRoomId, memberId, lastReadAt);
        }

        @Test
        @DisplayName("실패 - lastReadAt이 null인 경우 IllegalArgumentException")
        void fail_lastReadAtNull() {
            // when & then
            assertThatThrownBy(() -> chatRoomMemberService.updateLastReadAt(10L, 1L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastReadAt is required");
        }

        @Test
        @DisplayName("실패 - 멤버가 존재하지 않는 경우 IllegalStateException")
        void fail_memberNotFound() {
            // given
            Instant lastReadAt = Instant.now();
            given(chatRoomMemberRepository.existsByChatRoom_IdAndMember_Id(10L, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> chatRoomMemberService.updateLastReadAt(10L, 1L, lastReadAt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Not a member");
        }
    }

    // ==================== canConnectNonBotRoom ====================
    @Nested
    @DisplayName("canConnectNonBotRoom")
    class CanConnectNonBotRoom {

        @Test
        @DisplayName("성공 - 존재하는 멤버인 경우 true 반환")
        void success_memberExists() {
            // given
            given(chatRoomMemberRepository.existsConnectableMember(10L, 1L)).willReturn(true);

            // when
            boolean result = chatRoomMemberService.canConnectNonBotRoom(10L, 1L);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("성공 - 멤버가 존재하지 않는 경우 false 반환")
        void success_memberNotExists() {
            // given
            given(chatRoomMemberRepository.existsConnectableMember(10L, 999L)).willReturn(false);

            // when
            boolean result = chatRoomMemberService.canConnectNonBotRoom(10L, 999L);

            // then
            assertThat(result).isFalse();
        }
    }
}
