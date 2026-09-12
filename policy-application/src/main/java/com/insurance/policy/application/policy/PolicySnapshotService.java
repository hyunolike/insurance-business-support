package com.insurance.policy.application.policy;

import com.insurance.policy.application.port.out.PolicyRepository;
import com.insurance.policy.domain.policy.InsuredRef;
import com.insurance.policy.domain.policy.Policy;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicySnapshot;
import com.insurance.policy.domain.policy.exception.PolicyNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 스냅샷 조회 — 이 저장소의 가장 중요한 유스케이스.
 *
 * <p>claims가 청구 접수 시점에 호출하고, 응답을 불변 복제본으로 저장한다.
 * 이후 claims의 모든 심사·재심사는 그 복제본만 보므로, <b>여기서 잘못된 답을 주면
 * 그 청구의 심사 근거가 영구히 잘못된다.</b>
 *
 * <p>계약서: 같은 {@code (policyNo, asOf, insuredRef, knownAt)}은 영원히 같은 응답.
 */
@Service
public class PolicySnapshotService {

    private final PolicyRepository policyRepository;
    private final Clock clock;

    public PolicySnapshotService(PolicyRepository policyRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.clock = clock;
    }

    /**
     * @param knownAt {@code null}이면 현재 시각 — "지금 아는 진실"
     */
    @Transactional(readOnly = true)
    public PolicySnapshot snapshot(PolicyNo policyNo, InsuredRef insuredRef,
                                   LocalDate asOf, Instant knownAt) {
        LocalDate today = LocalDate.now(clock);
        if (asOf.isAfter(today)) {
            throw new IllegalArgumentException(
                    "기준일(asOf)은 미래일 수 없습니다: asOf=%s, today=%s".formatted(asOf, today));
        }

        Instant effectiveKnownAt = knownAt == null ? Instant.now(clock) : knownAt;

        Policy policy = policyRepository.findAsOf(policyNo, asOf, effectiveKnownAt)
                .orElseThrow(() -> new PolicyNotFoundException(policyNo));

        // 피보험자가 다르면 "권한 없음"이 아니라 "없음"으로 답한다.
        // 403은 "그 계약이 존재한다"는 사실을 노출한다.
        if (!policy.insured().insuredRef().equals(insuredRef)) {
            throw new PolicyNotFoundException(policyNo);
        }

        return policy.snapshotAsOf(asOf, effectiveKnownAt);
    }
}
