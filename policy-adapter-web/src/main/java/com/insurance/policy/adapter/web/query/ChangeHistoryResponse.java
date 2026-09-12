package com.insurance.policy.adapter.web.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insurance.policy.application.port.out.PolicyRepository.ChangeHistoryEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약 변경 이력.
 *
 * <p>두 시간축을 <b>둘 다</b> 보여준다. 운영자가 민원에 답하려면
 * "언제부터 그랬나"({@code validFrom}~{@code validTo})와
 * "우리가 언제 그렇게 알았나"({@code recordedAt}~{@code supersededAt})가 모두 필요하다.
 *
 * <p>{@code supersededByCorrection}이 핵심 구분이다.
 * <ul>
 *   <li>{@code false} — 변경으로 대체됨. "그때는 맞았고, 그 뒤에 바뀌었다"</li>
 *   <li>{@code true}  — 정정으로 대체됨. "그때도 틀렸었다". 이 기록으로 내린 판단은 다시 봐야 한다</li>
 * </ul>
 *
 * <p>무효화된 기록을 숨기지 않는다. 숨기면 정정 사실 자체가 이력에서 사라진다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChangeHistoryResponse(
        String policyNo,
        int count,
        List<Entry> entries
) {

    public static ChangeHistoryResponse of(String policyNo, List<ChangeHistoryEntry> entries) {
        return new ChangeHistoryResponse(policyNo, entries.size(),
                entries.stream().map(Entry::from).toList());
    }

    public record Entry(
            String element,
            String changeType,
            String targetRef,
            LocalDate validFrom,
            LocalDate validTo,
            Instant recordedAt,
            Instant supersededAt,
            boolean current,
            String detail,
            String reason,
            String actorRef
    ) {
        static Entry from(ChangeHistoryEntry e) {
            return new Entry(e.element(), e.changeType(), e.targetRef(),
                    e.validFrom(), e.validTo(), e.recordedAt(), e.supersededAt(),
                    e.supersededAt() == null,
                    e.detail(), e.reason(), e.actorRef());
        }
    }
}
