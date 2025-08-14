package likelion.mlb.backendProject.domain.match.scheduler;

import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import likelion.mlb.backendProject.domain.match.service.DraftTimingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * # MatchBootstrapper
 *
 * 애플리케이션 기동 시점(ApplicationReady)에 **다음 라운드**의
 * openAt/lockAt 기준 스케줄을 1회 등록한다.
 *
 * - 과거 시각 보정(이미 open/lock을 지난 경우의 즉시 이벤트 발사)은
 *   {@link MatchScheduler#scheduleFor(RoundInfo)}가 처리한다.
 * - 이후 라운드 간 재예약은 {@code LOCKED} 처리 직후
 *   {@link likelion.mlb.backendProject.domain.match.event.MatchEventsListener} 가 담당한다.
 *
 * ## 동작 요약
 * - 성공: 라운드가 확정되어 있으면 scheduleFor(...) 호출
 * - 스킵: 라운드 미확정 등으로 조회 실패 시, "정상 스킵" 로그만 남기고 종료
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchBootstrapper {

    private final DraftTimingService draftTimingService;
    private final MatchScheduler matchScheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            RoundInfo next = draftTimingService.getNextDraftWindowOrThrow();

            log.info("🚀 Bootstrap scheduling: roundId={}, openAt={}, lockAt={}",
                    next.getId(), next.getOpenAt(), next.getLockAt());

            matchScheduler.scheduleFor(next);
        } catch (Exception e) {
            // 아직 라운드 미확정 등 정상 케이스를 INFO로 남기고 넘어간다.
            log.info("Bootstrap skipped (no next round yet). reason={}", e.getMessage());
        }
    }
}
