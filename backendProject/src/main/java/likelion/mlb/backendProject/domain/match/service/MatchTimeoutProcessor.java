package likelion.mlb.backendProject.domain.match.service;

import likelion.mlb.backendProject.domain.chat.service.ChatRoomService;
import likelion.mlb.backendProject.domain.draft.entity.Draft;
import likelion.mlb.backendProject.domain.draft.repository.DraftRepository;
import likelion.mlb.backendProject.domain.match.dto.AssignDto;
import likelion.mlb.backendProject.domain.match.entity.Participant;
import likelion.mlb.backendProject.domain.match.infra.MatchKeys;
import likelion.mlb.backendProject.domain.match.repository.ParticipantRepository;
import likelion.mlb.backendProject.domain.round.entity.Round;
import likelion.mlb.backendProject.domain.round.repository.RoundRepository;
import likelion.mlb.backendProject.domain.user.entity.User;
import likelion.mlb.backendProject.global.exception.BaseException;
import likelion.mlb.backendProject.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;

/**
 * 타임아웃 시점 배치:
 * - 대기열(Redis Set: match:queue)을 스냅샷으로 읽고 4인 방 단위로 Draft/Participant 생성
 * - 실제 유저만 Redis에서 제거(SREM), 더미는 미제거
 * - (userId -> draftId, userNumber) 매핑을 반환해 WS로 DRAFT_START 푸시
 *
 * ⚠️ 중요: 반드시 "해당 라운드"로 저장해야 하므로, LOCKED 이벤트의 roundId를 파라미터로 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchTimeoutProcessor {

    private static final String DUMMY_PREFIX = "DUMMY:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DraftRepository draftRepository;
    private final ParticipantRepository participantRepository;
    private final RoundRepository roundRepository;
    private final ChatRoomService chatRoomService;

    @PersistenceContext
    private EntityManager em;

    /**
     * LOCKED 이벤트가 전달한 roundId 기준으로 배치를 수행한다.
     * @param roundId 방을 귀속시킬 라운드 ID (이 라운드에 Draft/Participant가 생성됨)
     */
    @Transactional
    public Map<String, AssignDto> processTimeoutAndInsert(UUID roundId) {
        // 1) 현재 대기 인원 스냅샷
        Set<String> members = Optional.ofNullable(
                redisTemplate.opsForSet().members(MatchKeys.QUEUE_KEY)
        ).orElse(Set.of());

        if (members.isEmpty()) {
            log.info("timeout: empty queue → no assignment");
            return Map.of();
        }

        // 공정성 위해 셔플
        List<String> users = new ArrayList<>(members);
        Collections.shuffle(users);

        // 2) 4인씩 그룹핑 + 마지막 그룹 패딩(더미)
        List<List<String>> groups = splitBySize(users, 4);
        if (!groups.isEmpty()) {
            List<String> last = groups.get(groups.size() - 1);
            if (last.size() < 4) padWithDummies(last);
        }

        // 3) 라운드 로딩(필수)
        Round round = roundRepository.findById(roundId)
                .orElseThrow(() -> new BaseException(ErrorCode.ROUND_NOT_FOUND));

        Map<String, AssignDto> result = new HashMap<>();
        List<String> usedRealUserIds = new ArrayList<>();

        // 4) 그룹별 Draft/Participant INSERT
        for (List<String> group : groups) {
            if (group.size() != 4) continue; // 방은 항상 4명

            Draft draft = new Draft();
            draft.setId(UUID.randomUUID());
            draft.setRound(round);
            draft.setDeleted(false);
            draftRepository.save(draft);

            // 필요 시 채팅방 생성(멀티 트랜잭션 고려 시 내부에서 별도 보호)
            chatRoomService.createRoom(draft.getId());

            short userNumber = 1;
            List<Participant> participants = new ArrayList<>(4);

            for (String uid : group) {
                Participant p = new Participant();
                p.setId(UUID.randomUUID());
                p.setDraft(draft);
                p.setUserNumber(userNumber++);

                if (uid.startsWith(DUMMY_PREFIX)) {
                    p.setDummy(true);
                    p.setUser(null);
                } else {
                    p.setDummy(false);
                    // ✅ 영속성 컨텍스트의 reference 사용(Transient 오류 방지)
                    UUID userUuid = UUID.fromString(uid);
                    User userRef = em.getReference(User.class, userUuid);
                    p.setUser(userRef);

                    // 결과 매핑
                    result.put(uid, new AssignDto(draft.getId(), (short) p.getUserNumber()));
                    usedRealUserIds.add(uid);
                }
                participants.add(p);
            }
            participantRepository.saveAll(participants);
        }

        // 5) 사용된 실제 유저만 Redis에서 제거
        if (!usedRealUserIds.isEmpty()) {
            redisTemplate.opsForSet().remove(
                    MatchKeys.QUEUE_KEY,
                    usedRealUserIds.toArray(new String[0]) // varargs 타입 안전
            );
        }

        log.info("✅ timeout done: assigned={} (groups={})", result.size(), groups.size());
        return result;
    }

    /**
     * (하위호환) roundId 없이 호출하는 구버전 API.
     * ⚠️ LOCKED 직후에는 '다음 라운드'로 잘못 꽂힐 수 있으니 사용 지양.
     */
    @Deprecated
    @Transactional
    public Map<String, AssignDto> processTimeoutAndInsert() {
        throw new IllegalStateException("Use processTimeoutAndInsert(roundId) with LOCKED event's roundId.");
    }

    /* ------------------ 유틸 ------------------ */

    /** n개씩 분할 */
    private static <T> List<List<T>> splitBySize(List<T> list, int n) {
        List<List<T>> res = new ArrayList<>();
        for (int i = 0; i < list.size(); i += n) {
            res.add(new ArrayList<>(list.subList(i, Math.min(i + n, list.size()))));
        }
        return res;
    }

    /** 4명이 될 때까지 더미 추가 */
    private static void padWithDummies(List<String> group) {
        int need = 4 - group.size();
        for (int i = 0; i < need; i++) {
            group.add(DUMMY_PREFIX + UUID.randomUUID());
        }
    }
}
