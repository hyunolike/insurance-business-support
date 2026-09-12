package com.insurance.policy.adapter.persistence.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스냅샷 캐시 설정.
 *
 * @param enabled Redis 없이 띄우는 환경(로컬·테스트)을 위해 끌 수 있어야 한다
 * @param ttl     캐시 대상이 불변이므로 정확성용이 아니라 메모리 회수용이다.
 *                짧게 잡으면 재심사 경로의 반복 조회가 매번 DB를 친다
 */
@ConfigurationProperties(prefix = "policy.snapshot.cache")
public record SnapshotCacheProperties(Boolean enabled, Duration ttl) {

    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    public SnapshotCacheProperties {
        enabled = enabled != null && enabled;
        ttl = ttl == null ? DEFAULT_TTL : ttl;

        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("캐시 TTL은 양수여야 합니다: " + ttl);
        }
    }
}
