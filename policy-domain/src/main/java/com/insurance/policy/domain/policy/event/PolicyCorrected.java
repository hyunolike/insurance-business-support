package com.insurance.policy.domain.policy.event;

import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * ★ 소급 정정 — "과거의 사실이 틀렸었다".
 *
 * <p><b>이 컨텍스트가 발행하는 이벤트 중 가장 중요하다.</b>
 *
 * <p>다른 이벤트는 "오늘부터 이렇게 됐다"는 통보라 이미 확정된 심사에 영향을 주지 않는다.
 * 정정은 그 심사의 <b>전제를 무너뜨린다</b>. 부담보가 애초에 없었다면, 그 부담보로
 * 부지급한 청구는 다시 봐야 한다.
 *
 * <p>claims의 반응:
 * <pre>
 * correctionScope.validFrom ~ validTo 구간에 사고일이 걸친 청구를 조회
 *   → 미지급 건  : 재심사 대상 표시 → 심사자 큐
 *   → 지급 완료 건: 추가지급 또는 환수 검토 → 심사자 큐
 *   → 자동 재심사하지 않는다. 정정 방향에 따라 결과가 반대일 수 있어 사람 판단이 필요하다
 * </pre>
 */
public record PolicyCorrected(
        EventId eventId,
        PolicyNo policyNo,
        LocalDate scopeValidFrom,
        LocalDate scopeValidTo,
        List<String> affectedElements,
        int previousSnapshotVersion,
        int newSnapshotVersion,
        String reason,
        String requestedBy,
        String approvedBy,
        Instant occurredAt
) implements DomainEvent {

    public PolicyCorrected(PolicyNo policyNo, LocalDate scopeValidFrom, LocalDate scopeValidTo,
                           List<String> affectedElements, int previousSnapshotVersion,
                           int newSnapshotVersion, String reason, String requestedBy,
                           String approvedBy, Instant at) {
        this(EventId.generate(at), policyNo, scopeValidFrom, scopeValidTo,
                List.copyOf(affectedElements), previousSnapshotVersion, newSnapshotVersion,
                reason, requestedBy, approvedBy, at);
    }

    @Override
    public String eventType() {
        return "policy.corrected";
    }

    @Override
    public String aggregateId() {
        return policyNo.value();
    }
}
