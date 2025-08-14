package likelion.mlb.backendProject.domain.match.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.mlb.backendProject.domain.match.dto.AssignDto;
import likelion.mlb.backendProject.domain.match.dto.MatchStatusResponse;
import likelion.mlb.backendProject.domain.match.ws.message.DraftStartMessage;
import likelion.mlb.backendProject.domain.match.ws.message.StatusMessage;
import likelion.mlb.backendProject.domain.match.ws.message.UserIdMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;

/**
 * # MatchBroadcaster
 *
 * WebSocket 전송 전담 컴포넌트.
 * - 세션 수명/조회는 {@link SessionRegistry}가 담당하고, 본 클래스는 **메시지 직렬화/전송**만 책임진다.
 * - 메시지 모델은 WS 전용 DTO({@code ws.message} 패키지)로 **평면 JSON**을 유지한다.
 *
 * ## 지원 메시지
 * - {@code USER_ID}: {"type":"USER_ID","userId":"..."}
 * - {@code STATUS}  : {"type":"STATUS","count":N,"remainingTime":"HH:MM:SS","state":"...","round":{...},"serverTime":"..."}
 * - {@code DRAFT_START}: {"type":"DRAFT_START","draftId":"UUID","userNumber":1..4}
 *
 * ## 주의
 * - 브로드캐스트 시 JSON은 **한 번만 직렬화**하여 모든 세션에 재사용한다.
 * - 전송 실패/닫힌 세션은 {@link SessionRegistry#remove(WebSocketSession)}로 정리한다.
 * - 멀티 인스턴스 환경에서도 Broadcaster는 **노드 로컬 세션**에만 전송한다(서비스 로직은 이벤트/락으로 보호).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchBroadcaster {

    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 연결 직후 USER_ID 1회 전송 */
    public void sendUserId(WebSocketSession session, String userId) {
        send(session, new UserIdMessage(userId));
    }

    /** 단일 세션에 STATUS 전송 (개별 state override 가능: 예) LOCKED_HOLD) */
    public void sendStatusTo(WebSocketSession session, MatchStatusResponse s, String overrideState) {
        var msg = new StatusMessage(
                s.getCount(),
                s.getRemainingTime(),
                (overrideState != null ? overrideState : s.getState()),
                s.getRound(),
                LocalDateTime.now(KST).toString() // serverTime (선택)
        );
        send(session, msg);
    }

    /** 전체 브로드캐스트: STATUS (override 없이 공통 상태 전송) */
    public void sendStatusToAll(MatchStatusResponse s) {
        var msg = new StatusMessage(
                s.getCount(), s.getRemainingTime(), s.getState(), s.getRound(),
                LocalDateTime.now(KST).toString()
        );
        String json = toJson(msg);
        broadcastRaw(json);
    }

    /** 선택 유저들에게만 STATUS 전송 (운영/테스트 편의) */
    public void sendStatusToUsers(Collection<String> userIds, MatchStatusResponse s, String overrideState) {
        var msg = new StatusMessage(
                s.getCount(), s.getRemainingTime(),
                (overrideState != null ? overrideState : s.getState()),
                s.getRound(),
                LocalDateTime.now(KST).toString()
        );
        String json = toJson(msg);
        for (String uid : userIds) {
            WebSocketSession sess = sessionRegistry.getByUser(uid);
            if (sess != null && sess.isOpen()) {
                try {
                    sess.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("STATUS(partial) 전송 실패 userId={}: {}", uid, e.getMessage());
                    sessionRegistry.remove(sess);
                }
            }
        }
    }

    /** 특정 유저에게 DRAFT_START 전송 */
    public void sendDraftStart(String userId, AssignDto assign) {
        var s = sessionRegistry.getByUser(userId);
        if (s == null || !s.isOpen()) {
            log.info("skip DRAFT_START: no active session for userId={}", userId);
            return;
        }
        var msg = DraftStartMessage.builder()
                .type("DRAFT_START")
                .draftId(assign.getDraftId())
                .userNumber(assign.getUserNumber())
                .build();

        log.info("➡️ DRAFT_START -> userId={}, draftId={}, userNumber={}",
                userId, assign.getDraftId(), assign.getUserNumber());

        send(s, msg);
    }

    /* -------------------- 내부 유틸 -------------------- */

    private void broadcastRaw(String json) {
        for (var s : sessionRegistry.all()) {
            if (s == null) continue;
            if (!s.isOpen()) {
                sessionRegistry.remove(s);
                continue;
            }
            try {
                s.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.warn("STATUS 브로드캐스트 실패: {}", e.getMessage());
                sessionRegistry.remove(s);
            }
        }
    }

    private void send(WebSocketSession session, Object payload) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(toJson(payload)));
        } catch (IOException e) {
            log.warn("WS 전송 실패: {}", e.getMessage());
            sessionRegistry.remove(session);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("직렬화 실패: {}", e.getMessage(), e);
            return "{}";
        }
    }
}
