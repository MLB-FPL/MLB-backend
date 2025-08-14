package likelion.mlb.backendProject.domain.match.infra;

import likelion.mlb.backendProject.domain.match.dto.MatchStatusResponse;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * STATUS 스냅샷 캐시.
 * - 매초 변하는 remainingTime은 무시하고,
 *   (count, state, roundId)만 비교하여 "의미 있는 변화"를 감지한다.
 * - updateIfChanged(now): 비교와 갱신을 원자적으로 수행.
 */
@Component
public class StatusCache {

    /** 마지막 스냅샷의 "의미 키"만 저장 */
    private final AtomicReference<Key> ref = new AtomicReference<>();

    /** 내부 비교 키 */
    private record Key(long count, String state, UUID roundId) {
        static Key of(MatchStatusResponse s) {
            UUID rid = (s.getRound() == null) ? null : s.getRound().getId();
            return new Key(s.getCount(), s.getState(), rid);
        }
    }

    /**
     * 현재 값(now)을 기준으로, 이전 스냅샷과 다르면
     * 캐시를 갱신하고 true 를 반환한다. (원자적)
     */
    public boolean updateIfChanged(MatchStatusResponse now) {
        Key next = Key.of(now);
        for (;;) {
            Key prev = ref.get();
            if (Objects.equals(prev, next)) {
                return false; // 변화 없음
            }
            if (ref.compareAndSet(prev, next)) {
                return true;  // 변화 감지 + 갱신 완료
            }
            // CAS 실패 시 재시도
        }
    }

    /* ====== 하위호환 메서드 (기존 호출부 유지용) ====== */

    /** remainingTime(매초 변함)은 비교에서 제외 */
    public boolean isChanged(MatchStatusResponse now) {
        Key before = ref.get();
        if (before == null) return true;
        Key next = Key.of(now);
        return !Objects.equals(before, next);
    }

    /** 기존 시그니처 유지용: 의미 키로 갱신 */
    public void update(MatchStatusResponse now) {
        ref.set(Key.of(now));
    }

    /* ====== 유틸 ====== */

    /** 캐시 초기화 */
    public void clear() { ref.set(null); }

    /** 스냅샷 존재 여부 */
    public boolean hasSnapshot() { return ref.get() != null; }
}
