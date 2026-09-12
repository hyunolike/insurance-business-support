package com.insurance.policy.domain.policy.event;

import com.insurance.policy.domain.policy.CoverageCode;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약 변경 — "오늘부터 바뀐다".
 *
 * <p>claims는 읽기모델만 갱신하고 <b>진행 중인 심사에 개입하지 않는다.</b>
 * 과거 스냅샷이 변하지 않았으므로 이미 내린 판단은 그대로 유효하다.
 */
public record PolicyEndorsed(
        EventId eventId,
        PolicyNo policyNo,
        List<CoverageCode> affectedCoverages,
        LocalDate effectiveFrom,
        String reason,
        String actorRef,
        Instant occurredAt
) implements DomainEvent {

    public PolicyEndorsed(PolicyNo policyNo, List<CoverageCode> affectedCoverages,
                          LocalDate effectiveFrom, String reason, String actorRef, Instant at) {
        this(EventId.generate(at), policyNo, List.copyOf(affectedCoverages), effectiveFrom,
                reason, actorRef, at);
    }

    @Override
    public String eventType() {
        return "policy.endorsed";
    }

    @Override
    public String aggregateId() {
        return policyNo.value();
    }
}
