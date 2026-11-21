import http from "k6/http";
import { check, sleep } from "k6";
import { BASE_URL } from "../../setup/config.js";
import { loadAccountSetup } from "../../setup/loadAccountSetup.js";
import { loginSetup } from "../../setup/loginSetup.js";

export const options = {
    scenarios: {
        perf_load: {
            executor: "ramping-vus",
            startVUs: 0,
            stages: [
                { duration: "2m", target: 200 },   // warm-up
                { duration: "3m", target: 500 },   // 40% load
                { duration: "5m", target: 1000 },  // ramp-up to peak
                { duration: "10m", target: 1000 }, // peak hold
                { duration: "3m", target: 500 },   // recover
                { duration: "2m", target: 0 },     // cool-down
            ],
            gracefulStop: "30s",
        },
    },
    thresholds: {
        http_req_failed: ["rate<0.01"],
        http_req_duration: ["p(95)<500", "p(99)<900"],
    },
    setupTimeout: "600s",
};

// 본인 회원정보 조회
const getMyInfo = `${BASE_URL}/rest-api/v1/member`;
// 루프 리포트 조회
const getLoopReport = `${BASE_URL}/rest-api/v1/report`;
// 루프 상세 조회 (유저별 loopId 동적)
const getDetailLoopUrl = (loopId) => `${BASE_URL}/rest-api/v1/loops/${loopId}`;
// 날짜별 루프 리스트 조회
const getDailyLoopsUrl = () => `${BASE_URL}/rest-api/v1/loops/date/${formatToday()}`;
// 루프 캘린더 조회
const getLoopCalendar = `${BASE_URL}/rest-api/v1/loops/calendar?year=2026&month=1`;
// ===== HIGH 우선순위 (7개) =====
// 내 상세 정보 조회
const getMyDetailInfo = `${BASE_URL}/rest-api/v1/member/detail`;
// 특정 멤버 상세 정보 조회 (유저별 memberId 동적)
const getMemberDetailInfoUrl = (memberId) => `${BASE_URL}/rest-api/v1/member/detail/${memberId}`;
// 내 팀 리스트 조회
const getMyTeams = `${BASE_URL}/rest-api/v1/teams/my`;
// 팀 상세 조회 (유저별 teamId 동적)
const getTeamDetailUrl = (teamId) => `${BASE_URL}/rest-api/v1/teams/${teamId}`;
// 팀 루프 리스트 조회 (유저별 teamId 동적)
const getTeamLoopsUrl = (teamId) => `${BASE_URL}/rest-api/v1/teams/${teamId}/loops`;
// 팀 루프 캘린더 조회 (유저별 teamId 동적)
const getTeamLoopCalendarUrl = (teamId) => `${BASE_URL}/rest-api/v1/teams/${teamId}/loops/calendar?year=2026&month=1`;
// 팀 멤버 활동 조회 (유저별 teamId 동적)
const getMemberActivitiesUrl = (teamId) => `${BASE_URL}/rest-api/v1/teams/${teamId}/member-activities`;
// ===== MEDIUM 우선순위 (5개) =====
// 특정 멤버 기본 정보 조회 (유저별 memberId 동적)
const getMemberInfoUrl = (memberId) => `${BASE_URL}/rest-api/v1/member/${memberId}`;
// 팀 멤버 리스트 조회 (유저별 teamId 동적)
const getTeamMembersUrl = (teamId) => `${BASE_URL}/rest-api/v1/teams/${teamId}/members`;
// 팀 루프 내 상세 조회 (유저별 teamId/teamLoopId 동적)
const getTeamLoopMyDetailUrl = (teamId, teamLoopId) => `${BASE_URL}/rest-api/v1/teams/${teamId}/loops/${teamLoopId}/my`;
// 팀 루프 전체 상세 조회 (유저별 teamId/teamLoopId 동적)
const getTeamLoopAllDetailUrl = (teamId, teamLoopId) => `${BASE_URL}/rest-api/v1/teams/${teamId}/loops/${teamLoopId}/all`;
// 팀 루프 체크리스트 조회 (유저별 loopId 동적)
const getChecklistStatusUrl = (loopId) => `${BASE_URL}/rest-api/v1/teams/loops/${loopId}/checklists`;

// -------------------- setup() --------------------
export function setup() {
    const MAX_SESSIONS = 1000;
    const emails = loadAccountSetup(MAX_SESSIONS);
    const sessions = loginSetup(emails);

    if (!sessions.length) {
        throw new Error("No sessions created in setup()");
    }
    return { sessions };
}

// -------------------- helpers --------------------
function formatToday() {
    const d = new Date();
    const yyyy = String(d.getFullYear());
    const mm = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    return `${yyyy}-${mm}-${dd}`;
}

function pickSession(data) {
    // VU 별로 안정적으로 하나 고르기: (__VU-1) 기준
    const sessions = data.sessions;
    const idx = (__VU - 1) % sessions.length;
    return sessions[idx];
}

function authParams(session) {
    return {
        cookies: session.cookies,
        headers: {
            "Content-Type": "application/json",
            // 추가 헤더 필요하면 여기
        },
    };
}

function get(url, session, name) {
    const res = http.get(url, authParams(session));
    check(res, {
        [`${name} status 200`]: (r) => r.status === 200,
    });
    return res;
}

function parseUserNo(session) {
    const key = String(session.userKey ?? "");
    const m = key.match(/user(\d+)\@/i) || key.match(/user(\d+)/i);
    if (m) return parseInt(m[1], 10);
}

// getDailyLoops 응답에서 첫 번째 loopId 추출
function extractFirstLoopId(res) {
    const id = res.json("data.loops[0].id");
    if (typeof id === "number" && id > 0) return id;
    return null;
}

// 10명 = 1팀: 1~10 -> 1, 11~20 -> 2 ...
function teamIdForUser(userNo) {
    return Math.floor((userNo - 1) / 10) + 1;
}

// 팀당 teamLoop 8개: team=1 -> 1, team=2 -> 9, team=3 -> 17 ...
function firstTeamLoopIdForTeam(teamId) {
    return 1 + (teamId - 1) * 8;
}

// 응답에서 첫 teamLoopId를 최대한 유연하게 뽑아보기
function extractFirstTeamLoopId(teamLoopsRes) {
    const candidatePaths = [
        "data[0].id",
        "data[0].teamLoopId",
        "data[0].loopId",
        "data.content[0].id",
        "data.content[0].teamLoopId",
        "data.content[0].loopId",
        "data.items[0].id",
        "data.items[0].teamLoopId",
    ];
    for (const path of candidatePaths) {
        const v = teamLoopsRes.json(path);
        if (typeof v === "number" && v > 0) return v;
    }
    for (const path of candidatePaths) {
        const v = teamLoopsRes.json(path);
        const n = parseInt(v, 10);
        if (!Number.isNaN(n) && n > 0) return n;
    }
    return null;
}

// -------------------- VU flow --------------------
export default function (data) {
    const session = pickSession(data);
    const userNo = parseUserNo(session);
    const teamId = teamIdForUser(userNo);
    const teamLoopId = firstTeamLoopIdForTeam(teamId);

    // ===== 기존 엔드포인트 (5개) =====
    get(getMyInfo, session, "getMyInfo");
    sleep(1);

    get(getLoopCalendar, session, "getLoopCalendar");
    sleep(1);

    const dailyLoopsRes = get(getDailyLoopsUrl(), session, "getDailyLoops");
    sleep(1);

    const loopId = extractFirstLoopId(dailyLoopsRes);
    if (loopId) {
        get(getDetailLoopUrl(loopId), session, "getDetailLoop");
        sleep(1);
    }

    get(getLoopReport, session, "getLoopReport");
    sleep(1);

    // ===== HIGH 우선순위 (7개) =====
    get(getMyDetailInfo, session, "getMyDetailInfo");
    sleep(1);

    get(getMemberDetailInfoUrl(userNo), session, `getMemberDetailInfo(memberId=${userNo})`);
    sleep(1);

    get(getMyTeams, session, "getMyTeams");
    sleep(1);

    get(getTeamDetailUrl(teamId), session, `getTeamDetail(teamId=${teamId})`);
    sleep(1);

    const teamLoopsRes = get(getTeamLoopsUrl(teamId), session, `getTeamLoops(teamId=${teamId})`);
    sleep(1);

    // teamLoopsRes에서 실제 teamLoopId 추출, 없으면 정적 계산값 사용
    const dynamicTeamLoopId = extractFirstTeamLoopId(teamLoopsRes) || teamLoopId;

    get(getTeamLoopCalendarUrl(teamId), session, `getTeamLoopCalendar(teamId=${teamId})`);
    sleep(1);

    get(getMemberActivitiesUrl(teamId), session, `getMemberActivities(teamId=${teamId})`);
    sleep(1);

    // ===== MEDIUM 우선순위 (5개) =====
    get(getMemberInfoUrl(userNo), session, `getMemberInfo(memberId=${userNo})`);
    sleep(1);

    get(getTeamMembersUrl(teamId), session, `getTeamMembers(teamId=${teamId})`);
    sleep(1);

    get(
        getTeamLoopMyDetailUrl(teamId, dynamicTeamLoopId),
        session,
        `getTeamLoopMyDetail(teamId=${teamId}, teamLoopId=${dynamicTeamLoopId})`
    );
    sleep(1);

    get(
        getTeamLoopAllDetailUrl(teamId, dynamicTeamLoopId),
        session,
        `getTeamLoopAllDetail(teamId=${teamId}, teamLoopId=${dynamicTeamLoopId})`
    );
    sleep(1);

    get(getChecklistStatusUrl(dynamicTeamLoopId), session, `getChecklistStatus(loopId=${dynamicTeamLoopId})`);
    sleep(1);
}
