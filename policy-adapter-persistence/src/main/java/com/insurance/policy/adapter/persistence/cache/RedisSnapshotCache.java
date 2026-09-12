package com.insurance.policy.adapter.persistence.cache;

import com.insurance.policy.application.port.out.SnapshotCache;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link SnapshotCache}의 Redis 구현.
 *
 * <p><b>★ 실패해도 요청을 깨뜨리지 않는다.</b> Redis가 죽었다고 스냅샷 API가 멈추면
 * claims의 청구 접수가 함께 멈춘다. 캐시는 있으면 좋은 것이지 가용성의 일부가 아니다.
 * 모든 예외를 잡아 로그만 남기고 DB 경로로 흘려보낸다.
 *
 * <p>TTL은 길게 잡는다. 캐시 대상이 <b>영원히 변하지 않는 값</b>이기 때문이다
 * ({@link SnapshotCache} 참고). TTL은 정확성 장치가 아니라 메모리 회수 장치다.
 *
 * <p>{@code StringRedisTemplate}을 쓴다. 직렬화 설정이 끼어들 여지가 없어야
 * 저장한 바이트가 그대로 돌아온다 — 체크섬이 그 바이트에서 나오기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "policy.snapshot.cache.enabled", havingValue = "true")
public class RedisSnapshotCache implements SnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSnapshotCache.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisSnapshotCache(StringRedisTemplate redis, SnapshotCacheProperties properties) {
        this.redis = redis;
        this.ttl = properties.ttl();
    }

    @Override
    public Optional<String> find(Key key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key.asRedisKey()));
        } catch (Exception e) {
            // 캐시 장애가 API 장애가 되지 않게 한다.
            log.warn("스냅샷 캐시 조회 실패 — DB로 진행합니다. key={}", key.asRedisKey(), e);
            return Optional.empty();
        }
    }

    @Override
    public void put(Key key, String responseJson) {
        try {
            redis.opsForValue().set(key.asRedisKey(), responseJson, ttl);
        } catch (Exception e) {
            log.warn("스냅샷 캐시 저장 실패 — 무시합니다. key={}", key.asRedisKey(), e);
        }
    }
}
