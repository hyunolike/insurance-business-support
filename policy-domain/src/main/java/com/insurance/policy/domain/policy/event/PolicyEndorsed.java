package com.insurance.policy.domain.policy.event;

import com.insurance.policy.domain.policy.CoverageCode;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 변경된 <b>담보 코드</b>만 싣고 바뀐 조건 값은 싣지 않는다.
     * 조건이 필요한 소비자는 스냅샷 API로 가져간다 — 이벤트는 "무엇이 바뀌었다"까지다.
     */
    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyNo", policyNo.value());
        p.put("affectedCoverages", affectedCoverages.stream().map(CoverageCode::value).toList());
        p.put("effectiveFrom", effectiveFrom.toString());
        p.put("reason", reason);
        p.put("actorRef", actorRef);
        return p;
    }
}
