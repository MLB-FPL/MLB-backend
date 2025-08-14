package likelion.mlb.backendProject.domain.match.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * # SessionRegistry
 *
 * WebSocket 세션을 **유저 단위로 1개**만 유지하는 레지스트리.
 *
 * ## 역할
 * - 세션 추가/제거 및 유저별 최신 세션 매핑 유지
 * - 브로드캐스트 대상 조회 (전체/열린 세션 스냅샷)
 * - 특정 유저 세션 강제 종료/닫힌 세션 정리(운영 편의)
 *
 * ## 사용 팁
 * - 핸들러 연결 시: {@code add(userId, session)}
 * - 핸들러 종료 시: {@code remove(session)}
 * - 개인 푸시: {@code getByUser(userId)}로 최신 세션 획득 후 전송
 * - 브로드캐스트: {@code allOpenSnapshot()}으로 열린 세션만 순회 권장
 *
 * ## 주의
 * - 세션 attribute의 사용자 키는 {@link #ATTR_USER_ID}로 통일한다.
 * - 닫힌 세션은 즉시 정리되지만, 장기 운영시 {@link #pruneClosed()}로 청소 가능.
 */
@Slf4j
@Component
public class SessionRegistry {

    /** 세션 attribute 키(전역 통일) */
    public static final String ATTR_USER_ID = "userId";

    /** 전체 세션(브로드캐스트 대상) */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /** 개인 푸시용: 유저ID → 최신 세션 */
    private final Map<String, WebSocketSession> latestByUser = new ConcurrentHashMap<>();

    /** 유저당 최신 세션만 유지 (이전 세션은 정상 종료) */
    public void add(String userId, WebSocketSession session) {
        session.getAttributes().put(ATTR_USER_ID, userId);

        WebSocketSession prev = latestByUser.put(userId, session);
        if (prev != null && prev != session && prev.isOpen()) {
            try {
                prev.close(CloseStatus.NORMAL);
                log.debug("Closed previous WS session. userId={}, prevSession={}", userId, prev.getId());
            } catch (Exception ignore) {
                // no-op
            }
        }
        sessions.add(session);
        log.debug("Session added. sessionId={}, userId={}, totalSessions={}, activeUsers={}",
                safeId(session), userId, sessions.size(), latestByUser.size());
    }

    /** 세션 종료/이탈 시 정리 */
    public void remove(WebSocketSession session) {
        sessions.remove(session);
        String uid = getUserId(session);
        if (uid != null) {
            latestByUser.compute(uid, (k, v) -> (v == session) ? null : v);
        }
        log.debug("Session removed. sessionId={}, userId={}, totalSessions={}, activeUsers={}",
                safeId(session), uid, sessions.size(), latestByUser.size());
    }

    /** 개인 푸시용: 최신 세션 조회 (없거나 닫히면 null 반환) */
    public WebSocketSession getByUser(String userId) {
        WebSocketSession s = latestByUser.get(userId);
        if (s == null) return null;
        if (!s.isOpen()) {
            // 닫혀 있으면 정리
            latestByUser.remove(userId, s);
            sessions.remove(s);
            return null;
        }
        return s;
    }

    /** 브로드캐스트 대상 전체(원본 Set) — 동시 이터레이션 가능(Concurrent Set) */
    public Collection<WebSocketSession> all() {
        return sessions;
    }

    /** 브로드캐스트용: 열린 세션의 "스냅샷"만 반환 + 닫힌 세션 정리 */
    public List<WebSocketSession> allOpenSnapshot() {
        return sessions.stream()
                .filter(s -> {
                    boolean open = s.isOpen();
                    if (!open) { sessions.remove(s); }
                    return open;
                })
                .collect(Collectors.toList());
    }

    /** 특정 유저의 세션을 강제 종료 */
    public void closeUser(String userId, CloseStatus status) {
        WebSocketSession s = latestByUser.remove(userId);
        if (s != null) {
            sessions.remove(s);
            if (s.isOpen()) {
                try { s.close(status != null ? status : CloseStatus.NORMAL); }
                catch (Exception ignore) { /* no-op */ }
            }
            log.info("Closed user session. userId={}, sessionId={}", userId, safeId(s));
        }
    }

    /** 닫힌 세션 일괄 정리(운영 점검/주기적 청소용) */
    public int pruneClosed() {
        int[] removed = {0};
        sessions.removeIf(s -> {
            boolean rm = !s.isOpen();
            if (rm) {
                String uid = getUserId(s);
                if (uid != null) latestByUser.remove(uid, s);
                removed[0]++;
            }
            return rm;
        });
        if (removed[0] > 0) {
            log.info("Pruned {} closed WS sessions. totalSessions={}, activeUsers={}",
                    removed[0], sessions.size(), latestByUser.size());
        }
        return removed[0];
    }

    /** 메트릭/디버깅 */
    public int sessionCount() { return sessions.size(); }
    public int activeUserCount() { return latestByUser.size(); }
    public String getUserId(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_USER_ID);
        return (v == null) ? null : v.toString();
    }

    private static String safeId(WebSocketSession s) {
        try { return (s == null) ? "null" : s.getId(); }
        catch (Throwable t) { return "unknown"; }
    }
}
