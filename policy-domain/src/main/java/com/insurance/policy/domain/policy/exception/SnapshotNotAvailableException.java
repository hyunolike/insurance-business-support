package com.insurance.policy.domain.policy.exception;

import com.insurance.policy.domain.policy.PolicyNo;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 요청한 시점의 계약 상태를 재현할 수 없음.
 *
 * <p>계약 생성 이전의 {@code asOf}를 조회했거나, {@code knownAt}이 최초 기록 시점보다
 * 앞선 경우다. API 계층에서 <b>404</b>로 매핑된다 — 그 시점에 이 계약은 존재하지 않았다.
 */
public class SnapshotNotAvailableException extends RuntimeException {

    private final transient PolicyNo policyNo;
    private final LocalDate asOf;
    private final Instant knownAt;

    public SnapshotNotAvailableException(PolicyNo policyNo, LocalDate asOf, Instant knownAt) {
        super("해당 시점의 계약 상태를 찾을 수 없습니다: policyNo=%s, asOf=%s, knownAt=%s"
                .formatted(policyNo, asOf, knownAt));
        this.policyNo = policyNo;
        this.asOf = asOf;
        this.knownAt = knownAt;
    }

    public PolicyNo policyNo() {
        return policyNo;
    }

    public LocalDate asOf() {
        return asOf;
    }

    public Instant knownAt() {
        return knownAt;
    }
}
