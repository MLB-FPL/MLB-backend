package likelion.mlb.backendProject.domain.match.service;

import likelion.mlb.backendProject.domain.match.dto.MatchStatusResponse;
import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import likelion.mlb.backendProject.domain.match.event.MatchQueueCanceledEvent;
import likelion.mlb.backendProject.domain.match.event.MatchQueueJoinedEvent;
import likelion.mlb.backendProject.domain.match.infra.MatchKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * # MatchService
 *
 * 매칭 대기열(Redis Set)을 관리하고,
 * 현재 드래프트 윈도우 상태 스냅샷을 계산/반환하는 서비스.
 *
 * - 대기열: {@code SADD/SREM} 기반으로 idempotent
 * - 상태 계산: DraftTimingService가 제공하는 라운드 윈도우(KST) 기준
 * - 이벤트:
 *   - {@link MatchQueueJoinedEvent}, {@link MatchQueueCanceledEvent} 발행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DraftTimingService draftTimingService;
    private final ApplicationEventPublisher publisher;

    /** 상태 계산 기준 타임존 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /* ------------------ 대기열 API ------------------ */

    /** 대기열 등록 (idempotent) + 이벤트 발행 */
    public void joinMatch(String userId) {
        if (userId == null || userId.isBlank()) return;
        redisTemplate.opsForSet().add(MatchKeys.QUEUE_KEY, userId);
        publisher.publishEvent(new MatchQueueJoinedEvent(userId));
    }

    /** 대기열 제거 + 이벤트 발행 */
    public void cancelMatch(String userId) {
        if (userId == null || userId.isBlank()) return;
        redisTemplate.opsForSet().remove(MatchKeys.QUEUE_KEY, userId);
        publisher.publishEvent(new MatchQueueCanceledEvent(userId));
    }

    /** 현재 대기열에 포함되어 있는가? (핸들러에서 LOCKED_HOLD 판단에 사용) */
    public boolean isInMatch(String userId) {
        if (userId == null || userId.isBlank()) return false;
        Boolean member = redisTemplate.opsForSet().isMember(MatchKeys.QUEUE_KEY, userId);
        return Boolean.TRUE.equals(member);
    }

    /* ------------------ 상태 스냅샷 ------------------ */

    /**
     * 대기 화면 상태 요약.
     * - BEFORE_OPEN: now < openAt  → 오픈까지 남은시간
     * - OPEN       : openAt ≤ now < lockAt → 마감까지 남은시간
     * - LOCKED     : now ≥ lockAt
     */
    public MatchStatusResponse getCurrentStatus() {
        long userCount = Optional.ofNullable(redisTemplate.opsForSet().size(MatchKeys.QUEUE_KEY)).orElse(0L);

        RoundInfo round = draftTimingService.getNextDraftWindowOrThrow();
        LocalDateTime now = LocalDateTime.now(KST);

        LocalDateTime openAt = LocalDateTime.parse(round.getOpenAt());
        LocalDateTime lockAt = LocalDateTime.parse(round.getLockAt());

        String state;
        String remaining;

        if (now.isBefore(openAt)) {
            state = "BEFORE_OPEN";
            remaining = formatRemaining(now, openAt);     // 오픈까지
        } else if (now.isBefore(lockAt)) {                // < lockAt (==이면 LOCKED)
            state = "OPEN";
            remaining = formatRemaining(now, lockAt);     // 마감까지
        } else {
            state = "LOCKED";
            remaining = "00:00:00";
        }

        return new MatchStatusResponse(userCount, state, remaining, round);
    }

    /**
     * “현재 시각 이후” 첫 라운드 윈도우. (없으면 null)
     * - 기존 호출부 호환용.
     */
    public RoundInfo getNextRoundWindowOrNull() {
        try {
            return draftTimingService.getNextDraftWindowOrThrow();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * “현재 라운드 이후”의 다음 라운드 윈도우. (없으면 null)
     * - 상태 전이(LOCKED) 이후 재예약 시 사용 권장.
     */
    public RoundInfo getNextRoundWindowAfterOrNull(UUID currentRoundId) {
        return draftTimingService.getNextDraftWindowAfterOrNull(currentRoundId);
    }

    /* ------------------ 내부 유틸 ------------------ */

    private String formatRemaining(LocalDateTime from, LocalDateTime to) {
        if (!to.isAfter(from)) return "00:00:00";
        long seconds = Duration.between(from, to).getSeconds();
        long hh = seconds / 3600;
        long mm = (seconds % 3600) / 60;
        long ss = seconds % 60;
        return String.format("%02d:%02d:%02d", hh, mm, ss);
    }
}
