package likelion.mlb.backendProject.domain.match.handler;

import likelion.mlb.backendProject.domain.match.dto.MatchStatusResponse;
import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import likelion.mlb.backendProject.domain.match.service.MatchService;
import likelion.mlb.backendProject.global.security.dto.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchHandler extends TextWebSocketHandler {

    private final MatchService matchService;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var principal = session.getPrincipal();
        if (!(principal instanceof Authentication auth) || !auth.isAuthenticated()) {
            log.warn("인증 정보가 없어 WebSocket 연결 종료");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // CustomUserDetails → userId
        Object p = auth.getPrincipal();
        String userId;
        if (p instanceof CustomUserDetails cud && cud.getUser() != null) {
            userId = cud.getUser().getId().toString();
        } else if (p instanceof String s) {
            userId = s; // 토큰의 subject를 그대로 쓰는 경우
        } else {
            log.warn("지원하지 않는 principal 타입: {}", p.getClass());
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        session.getAttributes().put("userId", userId);
        sessions.add(session);
        log.info("🟢 WebSocket 연결됨: {} (userId={})", session.getId(), userId);

        // 1) USER_ID 전송
        session.sendMessage(new TextMessage("{\"type\":\"USER_ID\",\"userId\":\"" + userId + "\"}"));

        // 2) STATUS 즉시 전송
        MatchStatusResponse status = matchService.getCurrentStatus();
        JSONObject response = new JSONObject();
        response.put("type", "STATUS");
        response.put("count", status.getCount());
        response.put("remainingTime", status.getRemainingTime());
        response.put("state", status.getState());

        JSONObject round = new JSONObject();
        round.put("no", status.getRound().getNo());
        round.put("id", status.getRound().getId());
        round.put("openAt", status.getRound().getOpenAt());
        round.put("lockAt", status.getRound().getLockAt());
        response.put("round", round);

        session.sendMessage(new TextMessage(response.toString()));
    }

    private String extractUserId(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal instanceof org.springframework.security.core.Authentication auth) {
            Object p = auth.getPrincipal();
            if (p instanceof CustomUserDetails cud) {
                return cud.getUser().getId().toString(); // UUID
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JSONObject json = new JSONObject(message.getPayload());
        String type = json.optString("type", "");

        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) {
            log.warn("세션에 userId 없음. 연결 종료");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        switch (type.toUpperCase()) {
            case "JOIN" -> {
                matchService.joinMatch(userId);
                log.info("JOIN 수신: {} -> Redis 등록", userId);
            }
            case "CANCEL" -> {
                matchService.cancelMatch(userId);
                log.info("CANCEL 수신: {} -> Redis 제거", userId);
                session.close();
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null && matchService.isInMatch(userId)) {
            matchService.cancelMatch(userId);
        }
    }

    @Scheduled(fixedRate = 1000)
    public void broadcastStatus() {
        MatchStatusResponse status = matchService.getCurrentStatus();
        JSONObject response = new JSONObject();
        response.put("type", "STATUS");
        response.put("count", status.getCount());
        response.put("remainingTime", status.getRemainingTime());
        response.put("state", status.getState());

        RoundInfo round = status.getRound();
        if (round != null) {
            JSONObject roundJson = new JSONObject();
            roundJson.put("id", round.getId().toString());
            roundJson.put("no", round.getNo());
            roundJson.put("openAt", round.getOpenAt());
            roundJson.put("lockAt", round.getLockAt());
            response.put("round", roundJson);
        }

        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) s.sendMessage(new TextMessage(response.toString()));
            } catch (IOException e) {
                log.warn("WebSocket 전송 실패: {}", e.getMessage());
                sessions.remove(s);
            }
        }
    }
}
