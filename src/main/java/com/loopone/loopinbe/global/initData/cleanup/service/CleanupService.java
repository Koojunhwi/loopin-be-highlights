package com.loopone.loopinbe.global.initData.cleanup.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService {

    private final DataSource dataSource;

    /**
     * user{N}@example.com 패턴의 테스트 데이터를 전부 hard delete 합니다.
     * 삭제 순서: 외래키 의존 관계의 역순으로 처리합니다.
     */
    public void deleteTestData() {
        log.info("[CLEANUP] 테스트 데이터 삭제 시작");
        long start = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // 1) 테스트 멤버 ID 조회
            List<Long> memberIds = queryIds(conn,
                    "SELECT id FROM member WHERE email ~ '^user[0-9]+@example\\.com'");
            log.info("[CLEANUP] 삭제 대상 멤버 수: {}", memberIds.size());

            if (memberIds.isEmpty()) {
                log.info("[CLEANUP] 삭제할 테스트 데이터가 없습니다.");
                return;
            }

            // 2) 테스트 팀 ID 조회 (team{N} 패턴)
            List<Long> teamIds = queryIds(conn,
                    "SELECT id FROM team WHERE name ~ '^team[0-9]+$'");
            log.info("[CLEANUP] 삭제 대상 팀 수: {}", teamIds.size());

            // 3) 테스트 멤버의 루프 ID 조회
            List<Long> loopIds = queryIds(conn,
                    "SELECT id FROM loop WHERE member_id IN (" + inClause(memberIds) + ")");
            log.info("[CLEANUP] 삭제 대상 루프 수: {}", loopIds.size());

            // 4) 테스트 멤버의 채팅방 ID 조회
            List<Long> chatRoomIds = queryIds(conn,
                    "SELECT id FROM chat_room WHERE member_id IN (" + inClause(memberIds) + ")");
            log.info("[CLEANUP] 삭제 대상 채팅방 수: {}", chatRoomIds.size());

            // === 삭제 시작 (외래키 역순) ===

            // 5) 팀 관련 삭제
            if (!teamIds.isEmpty()) {
                String teamIn = inClause(teamIds);
                // team_loop 관련
                List<Long> teamLoopIds = queryIds(conn,
                        "SELECT id FROM team_loop WHERE team_id IN (" + teamIn + ")");
                if (!teamLoopIds.isEmpty()) {
                    String teamLoopIn = inClause(teamLoopIds);
                    executeDelete(conn, "DELETE FROM team_loop_member_check WHERE member_progress_id IN (SELECT id FROM team_loop_member_progress WHERE team_loop_id IN (" + teamLoopIn + "))");
                    executeDelete(conn, "DELETE FROM team_loop_member_progress WHERE team_loop_id IN (" + teamLoopIn + ")");
                    executeDelete(conn, "DELETE FROM team_loop_checklist WHERE team_loop_id IN (" + teamLoopIn + ")");
                    executeDelete(conn, "DELETE FROM team_loop_activity WHERE team_loop_id IN (" + teamLoopIn + ")");
                    // team_loop의 loop_rule 정리
                    List<Long> teamLoopRuleIds = queryIds(conn,
                            "SELECT DISTINCT loop_rule_id FROM team_loop WHERE id IN (" + teamLoopIn + ") AND loop_rule_id IS NOT NULL");
                    executeDelete(conn, "DELETE FROM team_loop WHERE id IN (" + teamLoopIn + ")");
                    if (!teamLoopRuleIds.isEmpty()) {
                        executeDelete(conn, "DELETE FROM loop_rule_days_of_week WHERE loop_rule_id IN (" + inClause(teamLoopRuleIds) + ")");
                        executeDelete(conn, "DELETE FROM loop_rule WHERE id IN (" + inClause(teamLoopRuleIds) + ")");
                    }
                }

                executeDelete(conn, "DELETE FROM team_invitation WHERE team_id IN (" + teamIn + ")");
                executeDelete(conn, "DELETE FROM team_member WHERE team_id IN (" + teamIn + ")");
                // 팀 채팅방 삭제
                List<Long> teamChatRoomIds = queryIds(conn,
                        "SELECT id FROM chat_room WHERE team_id IN (" + teamIn + ")");
                if (!teamChatRoomIds.isEmpty()) {
                    executeDelete(conn, "DELETE FROM chat_room_member WHERE chat_room_id IN (" + inClause(teamChatRoomIds) + ")");
                    executeDelete(conn, "DELETE FROM chat_room WHERE id IN (" + inClause(teamChatRoomIds) + ")");
                }
                executeDelete(conn, "DELETE FROM team WHERE id IN (" + teamIn + ")");
            }

            // 6) 채팅방 관련 삭제 (멤버 소유)
            if (!chatRoomIds.isEmpty()) {
                String chatRoomIn = inClause(chatRoomIds);
                executeDelete(conn, "DELETE FROM chat_room_member WHERE chat_room_id IN (" + chatRoomIn + ")");
                executeDelete(conn, "DELETE FROM chat_room WHERE id IN (" + chatRoomIn + ")");
            }

            // 7) 루프 관련 삭제
            if (!loopIds.isEmpty()) {
                String loopIn = inClause(loopIds);
                executeDelete(conn, "DELETE FROM loop_checklist WHERE loop_id IN (" + loopIn + ")");
                // loop_rule 정리
                List<Long> loopRuleIds = queryIds(conn,
                        "SELECT DISTINCT loop_rule_id FROM loop WHERE id IN (" + loopIn + ") AND loop_rule_id IS NOT NULL");
                executeDelete(conn, "DELETE FROM loop WHERE id IN (" + loopIn + ")");
                if (!loopRuleIds.isEmpty()) {
                    String loopRuleIn = inClause(loopRuleIds);
                    // loop_rule은 다른 loop에서도 참조할 수 있으므로, 참조가 없는 것만 삭제
                    List<Long> orphanRuleIds = queryIds(conn,
                            "SELECT id FROM loop_rule WHERE id IN (" + loopRuleIn + ") AND id NOT IN (SELECT DISTINCT loop_rule_id FROM loop WHERE loop_rule_id IS NOT NULL)");
                    if (!orphanRuleIds.isEmpty()) {
                        String orphanIn = inClause(orphanRuleIds);
                        executeDelete(conn, "DELETE FROM loop_rule_days_of_week WHERE loop_rule_id IN (" + orphanIn + ")");
                        executeDelete(conn, "DELETE FROM loop_rule WHERE id IN (" + orphanIn + ")");
                    }
                }
            }

            // 8) 멤버 팔로우/팔로우 요청 삭제
            String memberIn = inClause(memberIds);
            executeDelete(conn, "DELETE FROM member_follow WHERE follow_id IN (" + memberIn + ") OR followed_id IN (" + memberIn + ")");
            executeDelete(conn, "DELETE FROM member_follow_req WHERE follow_req_id IN (" + memberIn + ") OR follow_rec_id IN (" + memberIn + ")");

            // 9) 남아있는 team_invitation (inviter/invitee로 참조)
            executeDelete(conn, "DELETE FROM team_invitation WHERE inviter_id IN (" + memberIn + ") OR invitee_id IN (" + memberIn + ")");

            // 10) 남아있는 team_member (다른 팀에 초대된 경우)
            executeDelete(conn, "DELETE FROM team_member WHERE member_id IN (" + memberIn + ")");

            // 11) 남아있는 chat_room_member (다른 채팅방에 참여한 경우)
            executeDelete(conn, "DELETE FROM chat_room_member WHERE member_id IN (" + memberIn + ")");

            // 12) 멤버 삭제
            executeDelete(conn, "DELETE FROM member WHERE id IN (" + memberIn + ")");

            conn.commit();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[CLEANUP] 테스트 데이터 삭제 완료. 소요시간: {}ms", elapsed);

        } catch (SQLException e) {
            log.error("[CLEANUP] 테스트 데이터 삭제 실패", e);
            throw new RuntimeException("테스트 데이터 삭제 실패", e);
        }
    }

    private List<Long> queryIds(Connection conn, String sql) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private void executeDelete(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            log.info("[CLEANUP] {} → {}건 삭제", extractTableName(sql), deleted);
        }
    }

    private String inClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private String extractTableName(String sql) {
        // "DELETE FROM table_name WHERE ..." 에서 테이블명 추출
        String upper = sql.toUpperCase();
        int fromIdx = upper.indexOf("FROM ") + 5;
        int endIdx = upper.indexOf(" ", fromIdx);
        if (endIdx == -1) endIdx = sql.length();
        return sql.substring(fromIdx, endIdx);
    }
}
