package likelion.mlb.backendProject.domain.match.event;

import likelion.mlb.backendProject.domain.match.dto.AssignDto;
import likelion.mlb.backendProject.domain.match.dto.MatchStatusResponse;
import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import likelion.mlb.backendProject.domain.match.infra.MatchBroadcaster;
import likelion.mlb.backendProject.domain.match.infra.RedisLock;
import likelion.mlb.backendProject.domain.match.infra.StatusCache;
import likelion.mlb.backendProject.domain.match.scheduler.MatchScheduler;
import likelion.mlb.backendProject.domain.match.service.MatchService;
import likelion.mlb.backendProject.domain.match.service.MatchTimeoutProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * # MatchEventsListener
 *
 * 매치 도메인의 **상태 변화 이벤트**에 반응하여:
 * 1) 상태 스냅샷을 브로드캐스트(STATUS),
 * 2) `LOCKED` 진입 시 타임아웃 배치를 실행하고(드래프트/참가자 생성 등),
 * 3) 개인별 `DRAFT_START` WebSocket 메시지를 푸시합니다.
 *
 * ## 구독 이벤트
 * - {@link MatchQueueJoinedEvent}: 대기열에 유저가 진입
 * - {@link MatchQueueCanceledEvent}: 대기열에서 유저가 이탈
 * - {@link MatchStateChangedEvent}: BEFORE_OPEN↔OPEN, OPEN→LOCKED 등 시간 전이
 *
 * ## 동작 개요
 * - 큐 변화 발생 시: 마지막 STATUS 스냅샷과 비교하여 **변경이 있을 때만** STATUS 브로드캐스트.
 * - 상태 전이 발생 시: STATUS를 즉시 브로드캐스트하고, `LOCKED`라면
 *   - 배정 배치({@link MatchTimeoutProcessor#processTimeoutAndInsert()})를 **1회만** 실행,
 *   - 결과 유저에게 `DRAFT_START`(type, draftId, userNumber) 메시지를 개별 푸시,
 *   - 다음 라운드가 확정되어 있으면 {@link MatchScheduler#scheduleFor}로 예약을 재등록.
 *
 * ## 동시성/중복 처리
 * - 단일 인스턴스 환경: {@code lastProcessedRoundId}로 라운드별 **1회 실행 보장**.
 * - 다중 인스턴스 환경: Redis 분산락(SETNX+TTL)을 사용해 배치 구간을 보호하세요.
 *
 * ## 메시지 포맷 (WebSocket)
 * - STATUS: {"type":"STATUS","count":Long,"state":"BEFORE_OPEN|OPEN|LOCKED[_HOLD]","round":{...}}
 * - DRAFT_START: {"type":"DRAFT_START","draftId":"UUID","userNumber":1..4}
 *
 * ## 참고
 * - 클라이언트 카운트다운은 서버의 openAt/lockAt 절대시각을 기준으로 **클라이언트에서 계산**합니다.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventsListener {

    private final MatchService matchService;
    private final MatchTimeoutProcessor timeoutProcessor;
    private final MatchBroadcaster broadcaster;
    private final StatusCache statusCache;
    private final MatchScheduler matchScheduler;

    /** 라운드별 LOCKED 처리 중복 방지(단일 인스턴스용). 멀티 인스턴스는 RedisLock 사용 권장. */
    private final AtomicReference<UUID> lastProcessedRoundId = new AtomicReference<>(null);
    private final RedisLock redisLock;

    /* ------------------------------------------
     *  큐 변화: 상태 스냅샷이 바뀌었을 때만 STATUS 브로드캐스트
     * ------------------------------------------ */

    @EventListener
    public void onQueueJoined(MatchQueueJoinedEvent e) {
        broadcastIfChanged();
    }

    @EventListener
    public void onQueueCanceled(MatchQueueCanceledEvent e) {
        broadcastIfChanged();
    }

    private void broadcastIfChanged() {
        MatchStatusResponse now = matchService.getCurrentStatus();
        if (statusCache.updateIfChanged(now)) {
            broadcaster.sendStatusToAll(now);
        }
    }

    /* ------------------------------------------
     *  상태 전이: 즉시 브로드캐스트 + LOCKED 배치 + 다음 라운드 스케줄
     * ------------------------------------------ */

    @EventListener
    public void onStateChanged(MatchStateChangedEvent e) {
        String prev = e.prev();
        String next = e.next();
        UUID roundId = e.roundId();

        log.info("StateChanged: {} -> {} (round={})", prev, next, roundId);

        // 상태 브로드캐스트(스냅샷 갱신 포함)
        MatchStatusResponse now = matchService.getCurrentStatus();
        if (statusCache.updateIfChanged(now)) {
            broadcaster.sendStatusToAll(now);
        }

        if (!"LOCKED".equalsIgnoreCase(next)) return;

        // 🔒 분산 락으로 한 번만 실행 (멀티 프로세스/Run-Standby 대비)
        String lockKey = "lock:match:round:" + roundId;
        boolean executed = redisLock.withLock(lockKey, java.time.Duration.ofSeconds(60), () -> {
            // (선택) 로컬 가드: 같은 노드에서 중복 진입 방지
            UUID prevProcessed = lastProcessedRoundId.get();
            if (prevProcessed != null && prevProcessed.equals(roundId)) {
                log.info("skip LOCKED processing (already processed locally) round={}", roundId);
                return;
            }
            lastProcessedRoundId.set(roundId);

            try {
                // ✅ 배정 배치 → 개인별 DRAFT_START 푸시
                Map<String, AssignDto> assignments = timeoutProcessor.processTimeoutAndInsert(e.roundId());
                log.info("assignments.size={}", assignments.size());
                assignments.forEach((uid, a) -> broadcaster.sendDraftStart(uid, a));

                // ▶ 다음 라운드 스케줄 재등록 (현재 라운드와 동일하면 스킵)
                RoundInfo nextRound = matchService.getNextRoundWindowAfterOrNull(roundId);
                if (nextRound != null && !nextRound.getId().equals(roundId)) {
                    matchScheduler.scheduleFor(nextRound);
                    log.info("scheduled next round={}", nextRound.getId());
                } else {
                    log.info("다음 라운드 없음 또는 아직 미확정");
                }
            } catch (Exception ex) {
                // 실패 시 재시도 허용 위해 로컬 가드 롤백
                lastProcessedRoundId.set(prevProcessed);
                throw ex;
            }
        });

        if (!executed) {
            // 다른 인스턴스(또는 Standby)가 선점하여 수행 중/완료됨
            log.info("LOCKED 처리 스킵(다른 노드 선점) round={}", roundId);
        }
    }

}
