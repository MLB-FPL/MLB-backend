package likelion.mlb.backendProject.domain.match.service;

import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import likelion.mlb.backendProject.domain.round.entity.Round;
import likelion.mlb.backendProject.domain.round.repository.RoundRepository;
import likelion.mlb.backendProject.global.exception.BaseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

/**
 * # DraftTimingService
 *
 * 라운드(Round.startedAt = 경기 시작 시각(UTC))를 기준으로
 * **드래프트 대기/등록 윈도우(openAt/lockAt, KST)** 를 계산해 돌려주는 서비스.
 *
 * - openAt/lockAt 정책:
 *   - `draftDay = startedAt(KST).toLocalDate() - 2일`
 *   - `openAt  = draftDay 08:00 KST`
 *   - `lockAt  = draftDay 14:35 KST`
 *
 * - 저장/조회 시각:
 *   - DB: `Round.startedAt` 는 `OffsetDateTime(UTC)`로 저장되어 있다고 가정
 *   - 서비스: 비교는 UTC, 계산/표시는 KST(LocalDateTime, ISO 문자열)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftTimingService {

    private final RoundRepository roundRepository;

    /** 비즈니스 시간대(KST) */
    private static final ZoneId  KST       = ZoneId.of("Asia/Seoul");
    /** UTC(저장/비교용) */
    private static final ZoneId  UTC       = ZoneOffset.UTC;
    /** 드래프트 오픈/락 시각(정책 상수) — 필요 시 application.properties로 분리 가능 */
    private static final LocalTime OPEN_TIME = LocalTime.of(8, 0);
    private static final LocalTime LOCK_TIME = LocalTime.of(17, 5);

    /**
     * "현재 시각 이후" 첫 라운드의 드래프트 윈도우를 반환한다.
     * - 라운드 미확정 시 {@link BaseException#ROUND_NOT_FOUND} 던짐.
     */
    public RoundInfo getNextDraftWindowOrThrow() {
        // 현재 UTC 기준 (명시적으로 UTC로 변환)
        OffsetDateTime nowUtc = OffsetDateTime.now(UTC);

        // now 이후 첫 라운드
        Round r = roundRepository.findFirstByStartedAtAfterOrderByStartedAtAsc(nowUtc);
        if (r == null || r.getStartedAt() == null) {
            throw BaseException.ROUND_NOT_FOUND;
        }
        return toWindow(r);
    }

    /**
     * 주어진 roundId "다음" 라운드의 드래프트 윈도우를 반환한다.
     * - 현재 라운드와 동일하면 제외(정확히 다음 것만)
     * - 없으면 null
     */
    public RoundInfo getNextDraftWindowAfterOrNull(UUID currentRoundId) {
        if (currentRoundId == null) return null;
        Optional<Round> curOpt = roundRepository.findById(currentRoundId);
        if (curOpt.isEmpty() || curOpt.get().getStartedAt() == null) return null;

        OffsetDateTime curStartedAt = curOpt.get().getStartedAt();
        Round next = roundRepository.findFirstByStartedAtAfterOrderByStartedAtAsc(curStartedAt);
        if (next == null) return null;
        return toWindow(next);
    }

    /* ================= 내부 유틸 ================= */

    /** Round(UTC) → RoundInfo(KST, ISO_LOCAL_DATETIME 문자열) 변환 */
    private RoundInfo toWindow(Round r) {
        // startedAt(UTC) → KST로 환산
        LocalDateTime startedAtKst = r.getStartedAt()
                .atZoneSameInstant(KST)  // OffsetDateTime → ZonedDateTime(KST)
                .toLocalDateTime();

        // 정책: 경기일(KST) 기준 2일 전이 draftDay
        LocalDate draftDay = startedAtKst.toLocalDate().minusDays(2);

        LocalDateTime openAt = LocalDateTime.of(draftDay, OPEN_TIME);
        LocalDateTime lockAt = LocalDateTime.of(draftDay, LOCK_TIME);

        // 방어: open < lock 보장(정책 상수이므로 보통 true)
        if (!openAt.isBefore(lockAt)) {
            log.warn("Invalid draft window computed. roundId={}, openAt={}, lockAt={}",
                    r.getId(), openAt, lockAt);
        }

        return new RoundInfo(
                r.getId(),
                r.getRound(),
                openAt.toString(), // ISO_LOCAL_DATE_TIME
                lockAt.toString()
        );
    }
}
