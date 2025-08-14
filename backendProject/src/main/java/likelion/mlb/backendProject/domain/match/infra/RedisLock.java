package likelion.mlb.backendProject.domain.match.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Redis 기반 분산 락 유틸.
 * - SETNX(key, token, PX=ttl) 로 잠금
 * - Lua 스크립트로 "내 토큰일 때만" 안전하게 unlock
 * - 비재진입(non-reentrant) 설계
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate redis;

    // token 일치할 때만 DEL
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    /**
     * 락 시도. 성공 시 소유 토큰 반환, 실패 시 null.
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(ok)) {
            return token;
        }
        return null;
    }

    /**
     * 락 해제. 내 토큰일 때만 삭제됨(타 노드 보호).
     */
    public void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("unlock 실패 key={}, cause={}", key, e.toString());
        }
    }

    /**
     * (옵션) 간단 래퍼: 락 획득 시 작업 실행, 실패 시 false 반환.
     */
    public boolean withLock(String key, Duration ttl, Runnable task) {
        String token = tryLock(key, ttl);
        if (token == null) return false;
        try {
            task.run();
            return true;
        } finally {
            unlock(key, token);
        }
    }
}
