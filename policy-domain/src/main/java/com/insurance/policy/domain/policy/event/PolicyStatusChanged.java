package com.insurance.policy.domain.policy.event;

import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 계약 상태 변경 (실효·부활·해지·만기).
 *
 * <p>이벤트 타입이 상태별로 갈린다 — claims의 소비자가 관심 있는 전이만 걸러 듣기 위함이다.
 *
 * <p>실효({@code policy.lapsed})는 납입최고 이력이 함께 의미를 갖는다.
 * 절차 없는 실효는 무효가 될 수 있으므로, Phase 5에서 최고 발송 시각이 페이로드에 추가된다.
 */
public record PolicyStatusChanged(
        EventId eventId,
        PolicyNo policyNo,
        PolicyStatus fromStatus,
        PolicyStatus toStatus,
        LocalDate effectiveFrom,
        String reason,
        String actorRef,
        Instant occurredAt
) implements DomainEvent {

    public PolicyStatusChanged(PolicyNo policyNo, PolicyStatus fromStatus, PolicyStatus toStatus,
                               LocalDate effectiveFrom, String reason, String actorRef,
                               Instant at) {
        this(EventId.generate(at), policyNo, fromStatus, toStatus, effectiveFrom,
                reason, actorRef, at);
    }

    @Override
    public String eventType() {
        return switch (toStatus) {
            case LAPSED -> "policy.lapsed";
            case REINSTATED -> "policy.reinstated";
            case SURRENDERED, MATURED, CANCELLED -> "policy.terminated";
            default -> "policy.status_changed";
        };
    }

    @Override
    public String aggregateId() {
        return policyNo.value();
    }

    /**
     * {@code eventType}이 전이별로 갈리지만 {@code fromStatus}/{@code toStatus}를 함께 싣는다.
     * 토픽만 보고 분기한 소비자가 나중에 정확한 전이를 알아야 할 때 이벤트를 다시 읽지 않게 한다.
     */
    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyNo", policyNo.value());
        p.put("fromStatus", fromStatus.name());
        p.put("toStatus", toStatus.name());
        p.put("effectiveFrom", effectiveFrom.toString());
        p.put("reason", reason);
        p.put("actorRef", actorRef);
        return p;
    }
}
