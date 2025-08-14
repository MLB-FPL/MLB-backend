package likelion.mlb.backendProject.domain.match.event;

/**
 * 매칭 큐에서 유저가 이탈했을 때 발생하는 도메인 이벤트.
 *
 * <p>발행 지점: MatchService.cancelMatch(userId)</p>
 * <p>소비 지점: MatchEventsListener.onQueueCanceled(...)</p>
 */
public record MatchQueueCanceledEvent(String userId) { }
