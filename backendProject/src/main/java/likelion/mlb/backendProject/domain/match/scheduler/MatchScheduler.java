package likelion.mlb.backendProject.domain.match.scheduler;

import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import likelion.mlb.backendProject.domain.match.event.MatchStateChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * # MatchScheduler
 *
 * 라운드의 openAt/lockAt 절대시각(KST)에 맞춰 도메인 이벤트를 발행하는 스케줄러.
 * - 과거 시각은 즉시 이벤트 발사(OPEN/LOCKED)
 * - 동일 라운드·동일 시각으로 재호출되면 **중복 예약을 스킵**해 무한 루프/중복 실행을 방지
 *
 * <h2>발행 이벤트</h2>
 * <ul>
 *   <li>{@code BEFORE_OPEN -> OPEN} : 오픈 시각</li>
 *   <li>{@code OPEN -> LOCKED}     : 락 시각</li>
 * </ul>
 *
 * <h2>스레드 안전성</h2>
 * - 예약 등록/취소는 {@code synchronized}로 직렬화
 * - 예약 핸들(openTask/lockTask) 및 예약 메타는 {@code volatile}
 *
 * <h2>타임존</h2>
 * - DB/외부에서 받은 {@code openAt, lockAt}은 {@code "yyyy-MM-dd'T'HH:mm:ss"}(tz 없음) 기준
 * - 본 스케줄러는 KST(Asia/Seoul)로 해석하여 Instant로 변환 후 스케줄 예약
 */
@Slf4j
@Component
public class MatchScheduler {

    /** match 도메인 전용 스케줄러 빈을 주입(@Qualifier 필요) */
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher publisher;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 예약 작업 핸들(취소/교체용) */
    private volatile ScheduledFuture<?> openTask;
    private volatile ScheduledFuture<?> lockTask;

    /** 현재 등록된 예약 메타(중복 호출 스킵용) */
    private volatile UUID   scheduledRoundId;
    private volatile Instant scheduledOpenInstant;
    private volatile Instant scheduledLockInstant;

    public MatchScheduler(@Qualifier("matchTaskScheduler") TaskScheduler taskScheduler,
                          ApplicationEventPublisher publisher) {
        this.taskScheduler = taskScheduler;
        this.publisher = publisher;
    }

    /**
     * 주어진 라운드의 open/lock 예약을 등록한다.
     * <ul>
     *   <li>기존 예약은 먼저 취소 후 새로 등록</li>
     *   <li>과거 시각이면 해당 이벤트를 즉시 발사</li>
     *   <li>openAt ≥ lockAt 인 비정상 윈도우는 경고 로그 후 LOCKED 즉시 발사</li>
     *   <li>이미 동일 라운드·동일 시각으로 예약되어 있다면 스킵</li>
     * </ul>
     */
    public synchronized void scheduleFor(RoundInfo r) {
        // 1) 문자열 시각 → KST → Instant
        LocalDateTime openLdt = LocalDateTime.parse(r.getOpenAt());
        LocalDateTime lockLdt = LocalDateTime.parse(r.getLockAt());
        Instant open = openLdt.atZone(KST).toInstant();
        Instant lock = lockLdt.atZone(KST).toInstant();

        // 2) 윈도우 유효성: open < lock
        if (!open.isBefore(lock)) {
            log.warn("Invalid window: openAt >= lockAt. roundId={}, openAt={}, lockAt={}",
                    r.getId(), r.getOpenAt(), r.getLockAt());
            fireLocked(r.getId()); // 보호적 처리: 바로 LOCKED로 넘김
            return;
        }

        // 3) 동일 라운드·동일 시각이면 스킵 (중복 예약 방지)
        if (Objects.equals(scheduledRoundId, r.getId())
                && Objects.equals(scheduledOpenInstant, open)
                && Objects.equals(scheduledLockInstant, lock)) {
            log.info("⏭️  skip scheduling (same round/times). roundId={}", r.getId());
            return;
        }

        // 4) 기존 예약 취소 후 새 예약 메타 반영
        cancelLocked();
        this.scheduledRoundId    = r.getId();
        this.scheduledOpenInstant = open;
        this.scheduledLockInstant = lock;

        Instant now = Instant.now();
        log.info("⏱️ scheduleFor: round={} openAt(KST)={} lockAt(KST)={}",
                r.getId(), r.getOpenAt(), r.getLockAt());

        // 5) OPEN 예약/즉시 발사
        if (open.isAfter(now)) {
            openTask = taskScheduler.schedule(() -> fireOpen(r.getId()), Date.from(open));
        } else if (lock.isAfter(now)) {
            fireOpen(r.getId()); // 이미 오픈 지남 → 1회 즉시 발사
        }

        // 6) LOCK 예약/즉시 발사
        if (lock.isAfter(now)) {
            lockTask = taskScheduler.schedule(() -> fireLocked(r.getId()), Date.from(lock));
        } else {
            fireLocked(r.getId()); // 이미 락 지남 → 1회 즉시 발사
        }
    }

    /**
     * 외부에서 예약을 취소할 때 사용.
     * 예약 핸들을 취소하고 메타를 초기화한다.
     */
    public synchronized void cancel() {
        cancelLocked();
        scheduledRoundId = null;
        scheduledOpenInstant = null;
        scheduledLockInstant = null;
    }

    /* ================= 내부 유틸 ================= */

    /** 예약 핸들만 취소(메타는 건드리지 않음) */
    private void cancelLocked() {
        if (openTask != null) openTask.cancel(false);
        if (lockTask != null) lockTask.cancel(false);
        openTask = null;
        lockTask = null;
    }

    /** BEFORE_OPEN -> OPEN 이벤트 발사 */
    private void fireOpen(UUID roundId) {
        try {
            log.info("🔥 OPEN fired for round={}", roundId);
            publisher.publishEvent(new MatchStateChangedEvent("BEFORE_OPEN", "OPEN", roundId));
        } catch (Exception e) {
            // 이벤트 발행 실패는 다음 예약 흐름에 영향이 없도록 로깅만
            log.error("OPEN fire failed. round={}, cause={}", roundId, e.toString(), e);
        }
    }

    /** OPEN -> LOCKED 이벤트 발사 */
    private void fireLocked(UUID roundId) {
        try {
            log.info("🔥 LOCKED fired for round={}", roundId);
            publisher.publishEvent(new MatchStateChangedEvent("OPEN", "LOCKED", roundId));
        } catch (Exception e) {
            log.error("LOCKED fire failed. round={}, cause={}", roundId, e.toString(), e);
        }
    }
}
