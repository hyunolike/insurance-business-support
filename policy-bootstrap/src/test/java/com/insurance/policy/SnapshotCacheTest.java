package com.insurance.policy;

import static com.insurance.policy.domain.policy.PolicyFixtures.POLICY_NO;
import static com.insurance.policy.domain.policy.PolicyFixtures.insured;
import static com.insurance.policy.domain.policy.PolicyFixtures.사고일;
import static com.insurance.policy.domain.policy.PolicyFixtures.부담보계약;
import static com.insurance.policy.domain.policy.PolicyFixtures.표준계약;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insurance.policy.adapter.web.snapshot.CachedSnapshotRenderer;
import com.insurance.policy.adapter.web.snapshot.CanonicalJson;
import com.insurance.policy.application.policy.PolicySnapshotService;
import com.insurance.policy.application.port.out.SnapshotCache;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 스냅샷 캐시.
 *
 * <p>Redis도 Spring도 띄우지 않는다. 검증할 것은 <b>무엇을 캐시하고 무엇을 캐시하지
 * 않는가</b>라는 판단이고, 그것은 저장소 종류와 무관하다.
 *
 * <p>이 판단이 틀리면 claims가 오래된 근거로 심사한다. 이 시스템에서 가장 나쁜 결과다.
 */
@DisplayName("스냅샷 캐시")
class SnapshotCacheTest {

    private static final Instant 청구접수 = Instant.parse("2026-04-02T01:15:00Z");
    private static final Instant 재심사 = Instant.parse("2026-06-01T00:00:00Z");

    private PolicySnapshotService service;
    private RecordingCache cache;
    private CachedSnapshotRenderer renderer;

    @BeforeEach
    void setUp() {
        service = mock(PolicySnapshotService.class);
        cache = new RecordingCache();
        renderer = new CachedSnapshotRenderer(service, new CanonicalJson(), provider(cache));
    }

    private void 스냅샷반환(com.insurance.policy.domain.policy.Policy policy, Instant knownAt) {
        when(service.snapshot(any(), any(), any(), any()))
                .thenReturn(policy.snapshotAsOf(사고일, knownAt));
    }

    private String render(Instant knownAt) {
        return renderer.render(POLICY_NO, insured().insuredRef(), 사고일, knownAt).checksum();
    }

    @Nested
    @DisplayName("★ 캐시해도 되는 것")
    class Cacheable {

        @Test
        @DisplayName("knownAt이 명시되면 캐시한다 — 두 시간축이 고정되면 답이 영원히 같다")
        void shouldCacheWhenKnownAtGiven() {
            스냅샷반환(부담보계약(), 청구접수);

            render(청구접수);

            assertThat(cache.stored).hasSize(1);
            assertThat(cache.stored.keySet()).singleElement()
                    .asString().contains(POLICY_NO.value());
        }

        @Test
        @DisplayName("두 번째 조회는 DB를 치지 않는다")
        void shouldServeSecondCallFromCache() {
            스냅샷반환(부담보계약(), 청구접수);

            render(청구접수);
            render(청구접수);

            verify(service, times(1)).snapshot(any(), any(), any(), any());
        }

        @Test
        @DisplayName("★★ 캐시 적중과 미적중이 같은 체크섬을 준다")
        void shouldReturnIdenticalChecksum() {
            스냅샷반환(부담보계약(), 청구접수);

            String 미적중 = render(청구접수);
            String 적중 = render(청구접수);

            assertThat(적중)
                    .as("적중 시 다시 렌더하면 Jackson 설정 변화에 따라 값이 달라질 수 있다. "
                            + "저장된 바이트를 그대로 돌려줘야 한다")
                    .isEqualTo(미적중);
        }
    }

    @Nested
    @DisplayName("★ 캐시하면 안 되는 것")
    class NotCacheable {

        @Test
        @DisplayName("★★ knownAt이 없으면 캐시하지 않는다 — 내일 답이 달라질 수 있다")
        void shouldNotCacheNowQuery() {
            스냅샷반환(부담보계약(), 재심사);

            renderer.render(POLICY_NO, insured().insuredRef(), 사고일, null);

            assertThat(cache.stored)
                    .as("정정이 나면 답이 바뀐다. 캐시에 남아 있으면 claims가 "
                            + "틀린 근거로 심사한다")
                    .isEmpty();
        }

        @Test
        @DisplayName("knownAt 없는 조회는 매번 DB를 친다")
        void shouldAlwaysHitDatabaseForNowQuery() {
            스냅샷반환(부담보계약(), 재심사);

            renderer.render(POLICY_NO, insured().insuredRef(), 사고일, null);
            renderer.render(POLICY_NO, insured().insuredRef(), 사고일, null);

            verify(service, times(2)).snapshot(any(), any(), any(), any());
        }

        @Test
        @DisplayName("캐시 키는 knownAt 없이 만들 수 없다 — 실수를 타입이 막는다")
        void shouldRejectKeyWithoutKnownAt() {
            assertThatThrownBy(() -> new SnapshotCache.Key(
                    POLICY_NO.value(), "CI-x", 사고일, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("캐시 대상이 아닙니다");
        }
    }

    @Nested
    @DisplayName("키 분리")
    class KeySeparation {

        @Test
        @DisplayName("★ 피보험자가 다르면 다른 키다 — 남의 계약을 받으면 안 된다")
        void shouldSeparateByInsuredRef() {
            var a = new SnapshotCache.Key("P2026-0001234", "CI-aaaa", 사고일, 청구접수);
            var b = new SnapshotCache.Key("P2026-0001234", "CI-bbbb", 사고일, 청구접수);

            assertThat(a.asRedisKey())
                    .as("키에서 빼면 서비스의 404 검사를 캐시가 우회시킨다")
                    .isNotEqualTo(b.asRedisKey());
        }

        @Test
        @DisplayName("asOf·knownAt이 다르면 다른 키다")
        void shouldSeparateByBothTimeAxes() {
            var base = new SnapshotCache.Key("P2026-0001234", "CI-a", 사고일, 청구접수);
            var 다른사고일 = new SnapshotCache.Key(
                    "P2026-0001234", "CI-a", LocalDate.of(2026, 3, 15), 청구접수);
            var 다른시점 = new SnapshotCache.Key("P2026-0001234", "CI-a", 사고일, 재심사);

            assertThat(base.asRedisKey())
                    .isNotEqualTo(다른사고일.asRedisKey())
                    .isNotEqualTo(다른시점.asRedisKey());
        }

        @Test
        @DisplayName("서로 다른 계약이 서로의 캐시를 덮지 않는다")
        void shouldNotCollideAcrossPolicies() {
            스냅샷반환(부담보계약(), 청구접수);
            render(청구접수);

            스냅샷반환(표준계약(), 청구접수);
            renderer.render(com.insurance.policy.domain.policy.PolicyNo.of("P2026-0009999"),
                    insured().insuredRef(), 사고일, 청구접수);

            assertThat(cache.stored).hasSize(2);
        }
    }

    @Nested
    @DisplayName("캐시 장애")
    class Resilience {

        @Test
        @DisplayName("★ 캐시가 꺼져 있어도 정상 동작한다")
        void shouldWorkWithoutCache() {
            var noCache = new CachedSnapshotRenderer(
                    service, new CanonicalJson(), provider(null));
            스냅샷반환(부담보계약(), 청구접수);

            var response = noCache.render(POLICY_NO, insured().insuredRef(), 사고일, 청구접수);

            assertThat(response.checksum()).startsWith("sha256:");
        }

        @Test
        @DisplayName("★ 캐시 조회가 실패해도 DB로 응답한다 — 캐시 장애가 API 장애가 되지 않는다")
        void shouldFallBackWhenCacheFails() {
            var brokenCache = new SnapshotCache() {
                @Override
                public Optional<String> find(Key key) {
                    // RedisSnapshotCache 는 예외를 잡아 empty 를 돌려준다. 그 계약을 흉내낸다.
                    return Optional.empty();
                }

                @Override
                public void put(Key key, String responseJson) {
                    // 저장 실패도 조용히 넘어간다
                }
            };
            var renderer = new CachedSnapshotRenderer(
                    service, new CanonicalJson(), provider(brokenCache));
            스냅샷반환(부담보계약(), 청구접수);

            var response = renderer.render(POLICY_NO, insured().insuredRef(), 사고일, 청구접수);

            assertThat(response.checksum()).startsWith("sha256:");
        }
    }

    // ─── 테스트 더블 ──────────────────────────────────────────────────────

    /** 메모리 캐시. Redis 없이 캐시 판단만 검증한다. */
    private static class RecordingCache implements SnapshotCache {
        private final Map<String, String> stored = new HashMap<>();

        @Override
        public Optional<String> find(Key key) {
            return Optional.ofNullable(stored.get(key.asRedisKey()));
        }

        @Override
        public void put(Key key, String responseJson) {
            stored.put(key.asRedisKey(), responseJson);
        }
    }

    private static ObjectProvider<SnapshotCache> provider(SnapshotCache cache) {
        return new ObjectProvider<>() {
            @Override
            public SnapshotCache getIfAvailable() {
                return cache;
            }

            @Override
            public SnapshotCache getObject() {
                return cache;
            }

            @Override
            public SnapshotCache getObject(Object... args) {
                return cache;
            }

            @Override
            public SnapshotCache getIfUnique() {
                return cache;
            }
        };
    }
}
