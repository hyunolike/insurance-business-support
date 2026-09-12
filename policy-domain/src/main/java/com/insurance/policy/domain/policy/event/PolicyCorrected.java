package com.insurance.policy.domain.policy.event;

import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * ★ claims가 재심사 대상을 고르는 데 필요한 것만 싣는다:
     * <b>어느 구간이</b>({@code scopeValidFrom}~{@code scopeValidTo})
     * <b>무엇이</b>({@code affectedElements}) 바뀌었는가.
     *
     * <p>정정 전후의 값 자체는 싣지 않는다. 부담보 정정이라면 그 값이 곧 KCD 범위이고,
     * 그것은 건강정보다. claims는 스냅샷 API를 두 knownAt으로 두 번 불러 차이를 본다.
     */
    @Override
    public Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyNo", policyNo.value());
        p.put("scopeValidFrom", scopeValidFrom.toString());
        p.put("scopeValidTo", scopeValidTo == null ? null : scopeValidTo.toString());
        p.put("affectedElements", List.copyOf(affectedElements));
        p.put("previousSnapshotVersion", previousSnapshotVersion);
        p.put("newSnapshotVersion", newSnapshotVersion);
        p.put("reason", reason);
        p.put("requestedBy", requestedBy);
        p.put("approvedBy", approvedBy);
        return p;
    }
}
