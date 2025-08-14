package likelion.mlb.backendProject.domain.match.event;

/**
 * 매칭 큐에 유저가 진입했을 때 발생하는 도메인 이벤트.
 *
 * <p>발행 지점: MatchService.joinMatch(userId)</p>
 * <p>소비 지점: MatchEventsListener.onQueueJoined(...)</p>
 */
public record MatchQueueJoinedEvent(String userId) { }
