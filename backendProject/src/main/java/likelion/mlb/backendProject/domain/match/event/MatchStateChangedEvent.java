package likelion.mlb.backendProject.domain.match.event;

import java.util.UUID;

/**
 * 라운드 상태 전이 이벤트.
 *
 * <p>예시 전이:
 * <ul>
 *   <li>"BEFORE_OPEN" → "OPEN"</li>
 *   <li>"OPEN" → "LOCKED"</li>
 * </ul>
 * 허용 상태 문자열: "BEFORE_OPEN", "OPEN", "LOCKED"
 *
 * <p>발행 지점: MatchScheduler.scheduleFor(...)의 예약 트리거</p>
 * <p>소비 지점: MatchEventsListener.onStateChanged(...)</p>
 */
public record MatchStateChangedEvent(
        String prev,   // 이전 상태
        String next,   // 다음 상태
        UUID roundId   // 대상 라운드 ID
) { }
