package likelion.mlb.backendProject.domain.match.ws;

import likelion.mlb.backendProject.domain.match.infra.MatchBroadcaster;
import likelion.mlb.backendProject.domain.match.infra.SessionRegistry;
import likelion.mlb.backendProject.domain.match.service.MatchService;
import likelion.mlb.backendProject.global.security.dto.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * # MatchHandler (WebSocket)
 *
 * 매칭 화면에서 사용하는 경량 WS 핸들러.
 * - 연결 직후: USER_ID, STATUS 1회 전송
 * - 수신: {type:"JOIN"} / {type:"CANCEL"} 만 처리
 * - 상태 브로드캐스트는 이벤트 기반(리스너)에서 수행
 *
 * ## 수신 메시지 (클라이언트 → 서버)
 * - JOIN   : 대기열 등록
 * - CANCEL : 대기열 해제 후 연결 종료
 *
 * ## 발신 메시지 (서버 → 클라이언트)
 * - USER_ID     : {"type":"USER_ID","userId":"..."}
 * - STATUS      : {"type":"STATUS", ...}
 * - DRAFT_START : {"type":"DRAFT_START","draftId":"...","userNumber":1..4}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchHandler extends TextWebSocketHandler {

    private final MatchService matchService;
    private final SessionRegistry sessionRegistry;
    private final MatchBroadcaster broadcaster;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var principal = session.getPrincipal();
        if (!(principal instanceof Authentication auth) || !auth.isAuthenticated()) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        Object p = auth.getPrincipal();
        if (!(p instanceof CustomUserDetails cud)) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        String userId = cud.getUser().getId().toString();

        // 유저당 최신 세션 유지 (+ 세션 attribute에 userId 저장)
        sessionRegistry.add(userId, session);
        log.info("🟢 WebSocket 연결됨: {} (userId={})", safeId(session), userId);

        // 초기 메시지(끊김은 무시)
        try {
            broadcaster.sendUserId(session, userId);
            broadcaster.sendStatusTo(session, matchService.getCurrentStatus(), null);
        } catch (Exception ex) {
            log.warn("초기 메시지 전송 중 끊김: {}", ex.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String userId = sessionRegistry.getUserId(session);
        if (userId == null) {
            log.warn("세션에 userId 없음. 연결 종료");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        String type;
        try {
            JSONObject json = new JSONObject(message.getPayload());
            type = json.optString("type", "").trim().toUpperCase();
        } catch (Exception e) {
            log.warn("잘못된 WS 페이로드: {}", e.toString());
            return;
        }

        switch (type) {
            case "JOIN" -> {
                matchService.joinMatch(userId);
                log.info("JOIN 수신: {} -> Redis 등록", userId);
            }
            case "CANCEL" -> {
                matchService.cancelMatch(userId);
                log.info("CANCEL 수신: {} -> Redis 제거", userId);
                try { session.close(); } catch (Exception ignore) {}
            }
            default -> log.debug("알 수 없는 type 수신: {}", type);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("WS transport 에러: sessionId={}, cause={}", safeId(session), exception.toString());
        sessionRegistry.remove(session);
        try {
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception ignore) { /* no-op */ }
        // 선택: 부모 기본 처리 호출 (원하면 유지)
        super.handleTransportError(session, exception);
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.remove(session);
        log.info("🔴 WebSocket 종료: {} ({})", safeId(session), status);
    }

    private static String safeId(WebSocketSession s) {
        try { return (s == null) ? "null" : s.getId(); }
        catch (Throwable t) { return "unknown"; }
    }
}
