package com.insurance.policy.adapter.web.snapshot;

import com.insurance.policy.application.policy.PolicySnapshotService;
import com.insurance.policy.application.port.out.SnapshotCache;
import com.insurance.policy.domain.policy.InsuredRef;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.SnapshotChecksum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 스냅샷을 렌더하고, 안전한 경우에만 캐시한다.
 *
 * <h2>★ 캐시 적중과 미적중이 같은 바이트를 준다</h2>
 * 캐시하는 것은 도메인 객체가 아니라 <b>이미 렌더된 응답 JSON</b>이다.
 * 그래서 적중 시에도 체크섬이 재계산되지 않고, 저장해둔 바이트가 그대로 나간다.
 * 도메인 객체를 캐시하면 역직렬화 후 다시 렌더해야 하는데, 그 사이 Jackson 설정이
 * 바뀌면 같은 질의가 다른 체크섬을 내고 claims의 무결성 검증이 깨진다.
 *
 * <h2>★ 무엇을 캐시하지 않는가</h2>
 * {@code knownAt}이 없는 조회 — "지금 아는 진실"이라 내일 달라질 수 있다.
 * 정정이 일어나면 답이 바뀌는데, 그 순간 claims는 <b>틀린 근거로 심사한다.</b>
 * 무효화 로직으로 막을 수도 있지만 그 로직이 한 번 새면 되돌릴 방법이 없다.
 * 캐시 안 하는 쪽이 옳다 — 반복 조회가 잦은 재심사 경로는 어차피 knownAt을 명시한다.
 *
 * <p>캐시가 꺼져 있으면 {@link ObjectProvider}가 빈 값을 주고 그냥 렌더만 한다.
 * Redis 없이도 애플리케이션이 뜬다.
 */
@Component
public class CachedSnapshotRenderer {

    private final PolicySnapshotService snapshotService;
    private final CanonicalJson canonicalJson;
    private final ObjectProvider<SnapshotCache> cacheProvider;

    public CachedSnapshotRenderer(PolicySnapshotService snapshotService,
                                  CanonicalJson canonicalJson,
                                  ObjectProvider<SnapshotCache> cacheProvider) {
        this.snapshotService = snapshotService;
        this.canonicalJson = canonicalJson;
        this.cacheProvider = cacheProvider;
    }

    /**
     * @param knownAt {@code null}이면 "지금". 이 경우 캐시하지 않는다
     * @return 체크섬까지 채워진 응답
     */
    public PolicySnapshotResponse render(PolicyNo policyNo, InsuredRef insuredRef,
                                         LocalDate asOf, Instant knownAt) {
        Optional<SnapshotCache.Key> key = cacheKeyFor(policyNo, insuredRef, asOf, knownAt);
        SnapshotCache cache = cacheProvider.getIfAvailable();

        if (cache != null && key.isPresent()) {
            Optional<String> cached = cache.find(key.get());
            if (cached.isPresent()) {
                return PolicySnapshotResponse.fromCachedJson(cached.get());
            }
        }

        var snapshot = snapshotService.snapshot(policyNo, insuredRef, asOf, knownAt);
        PolicySnapshotResponse body = PolicySnapshotResponse.from(snapshot);
        String checksum = SnapshotChecksum.of(canonicalJson.of(body)).value();
        PolicySnapshotResponse complete = body.withChecksum(checksum);

        if (cache != null && key.isPresent()) {
            cache.put(key.get(), canonicalJson.serialize(complete));
        }
        return complete;
    }

    /**
     * 캐시해도 되는 질의인가.
     *
     * <p>{@code knownAt}이 명시되어야 한다. 두 시간축이 모두 고정되면 이력이
     * append-only이므로 답이 영원히 같다 — 무효화가 필요 없다.
     */
    private Optional<SnapshotCache.Key> cacheKeyFor(PolicyNo policyNo, InsuredRef insuredRef,
                                                    LocalDate asOf, Instant knownAt) {
        if (knownAt == null) {
            return Optional.empty();
        }
        return Optional.of(new SnapshotCache.Key(
                policyNo.value(), insuredRef.value(), asOf, knownAt));
    }
}
