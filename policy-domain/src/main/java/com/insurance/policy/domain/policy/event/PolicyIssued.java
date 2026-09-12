package com.insurance.policy.domain.policy.event;

import com.insurance.policy.domain.policy.CoverageCode;
import com.insurance.policy.domain.policy.InsuredRef;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicyPeriod;
import com.insurance.policy.domain.policy.ProductRef;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약 성립.
 *
 * <p>claims는 이 이벤트로 읽기모델({@code policy_replica})을 만든다.
 * 접수 화면의 계약 목록과 명백한 오류(존재하지 않는 계약번호) 조기 차단에 쓰이며,
 * <b>심사 판정에는 쓰이지 않는다</b> — 심사는 스냅샷만 본다.
 *
 * <p>부담보 상세(KCD 범위)를 싣지 않고 {@code hasExclusions} 여부만 알리는 이유:
 * KCD 범위는 건강정보를 추론할 수 있는 민감정보다. 필요하면 claims가
 * 스냅샷 API로 인가받아 가져간다.
 */
public record PolicyIssued(
        EventId eventId,
        PolicyNo policyNo,
        ProductRef product,
        InsuredRef insuredRef,
        PolicyPeriod policyPeriod,
        LocalDate effectiveDate,
        List<CoverageCode> coverageCodes,
        boolean hasExclusions,
        Instant occurredAt
) implements DomainEvent {

    public PolicyIssued(PolicyNo policyNo, ProductRef product, InsuredRef insuredRef,
                        PolicyPeriod policyPeriod, LocalDate effectiveDate,
                        List<CoverageCode> coverageCodes, boolean hasExclusions, Instant at) {
        this(EventId.generate(at), policyNo, product, insuredRef, policyPeriod, effectiveDate,
                List.copyOf(coverageCodes), hasExclusions, at);
    }

    @Override
    public String eventType() {
        return "policy.issued";
    }

    @Override
    public String aggregateId() {
        return policyNo.value();
    }
}
