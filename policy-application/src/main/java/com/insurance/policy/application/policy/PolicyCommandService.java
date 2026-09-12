package com.insurance.policy.application.policy;

import com.insurance.policy.application.port.out.OutboxAppender;
import com.insurance.policy.application.port.out.PolicyRepository;
import com.insurance.policy.domain.policy.Coverage;
import com.insurance.policy.domain.policy.CoverageCode;
import com.insurance.policy.domain.policy.CoverageTerms;
import com.insurance.policy.domain.policy.Exclusion;
import com.insurance.policy.domain.policy.ExclusionId;
import com.insurance.policy.domain.policy.InsuredInfo;
import com.insurance.policy.domain.policy.Policy;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicyPeriod;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.policy.ProductRef;
import com.insurance.policy.domain.policy.exception.PolicyNotFoundException;
import com.insurance.policy.domain.shared.vo.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 변경 유스케이스.
 *
 * <p><b>모든 메서드가 같은 패턴을 따른다:</b>
 * <pre>
 *   애그리거트 로드 → 도메인 메서드 호출(이벤트를 record) → 저장 → pullEvents()를 Outbox에 append
 * </pre>
 *
 * <p>{@code @Transactional} 안에서 Kafka로 직접 발행하지 않는다. Outbox 테이블에 INSERT만 하며,
 * 커밋되면 이벤트도 있고 롤백되면 이벤트도 없다.
 *
 * <p>이 컨텍스트에서 특히 중요한 이유: {@code policy.corrected}가 롤백됐는데 이벤트만 나가면
 * claims가 멀쩡한 청구를 재심사 큐에 쌓고, 유실되면 틀린 근거로 계속 지급한다.
 */
@Service
public class PolicyCommandService {

    private final PolicyRepository policyRepository;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    public PolicyCommandService(PolicyRepository policyRepository,
                                OutboxAppender outboxAppender,
                                Clock clock) {
        this.policyRepository = policyRepository;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    @Transactional
    public PolicyNo issue(IssuePolicyCommand cmd) {
        Instant now = Instant.now(clock);
        Policy policy = Policy.issue(cmd.policyNo(), cmd.product(), cmd.holderRef(), cmd.insured(),
                cmd.period(), cmd.acceptedOn(), cmd.firstPremiumPaidOn(),
                cmd.coverages(), cmd.exclusions(),
                cmd.applicationNo(), cmd.uwCaseNo(), now);

        policyRepository.save(policy);
        outboxAppender.append(policy.pullEvents());
        return policy.policyNo();
    }

    /** 변경 — "오늘부터 바뀐다". 과거 스냅샷은 그대로다. */
    @Transactional
    public void endorse(EndorseCoverageCommand cmd) {
        Instant now = Instant.now(clock);
        Policy policy = policyRepository.findCurrent(cmd.policyNo())
                .orElseThrow(() -> new PolicyNotFoundException(cmd.policyNo()));

        policy.endorseCoverage(cmd.coverageCode(), cmd.newTerms(), cmd.newInsuredAmount(),
                cmd.effectiveFrom(), cmd.reason(), cmd.actorRef(), now);

        policyRepository.applyEndorsement(policy);
        outboxAppender.append(policy.pullEvents());
    }

    /**
     * 정정 — "과거가 원래 그랬다". claims의 재심사를 유발한다.
     *
     * <p>요청자와 승인자가 달라야 한다. 도메인이 강제하고 DB 제약이 한 번 더 막는다.
     */
    @Transactional
    public void correctExclusion(CorrectExclusionCommand cmd) {
        Instant now = Instant.now(clock);
        Policy policy = policyRepository.findCurrent(cmd.policyNo())
                .orElseThrow(() -> new PolicyNotFoundException(cmd.policyNo()));

        var target = policy.allExclusions().stream()
                .filter(e -> e.exclusionId().equals(cmd.exclusionId()))
                .filter(e -> e.supersededAt() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "정정 대상 부담보를 찾을 수 없습니다: " + cmd.exclusionId()));

        int previousVersion = policy.snapshotAsOf(target.validFrom(), now).snapshotVersion();

        policy.correctExclusion(cmd.exclusionId(), cmd.replacement(), cmd.reason(),
                cmd.requestedBy(), cmd.approvedBy(), now);

        policyRepository.applyCorrection(policy, new PolicyRepository.CorrectionRecord(
                cmd.policyNo(),
                cmd.replacement() == null ? "REMOVE_EXCLUSION" : "REPLACE_EXCLUSION",
                List.of("EXCLUSION"),
                target.validFrom(), target.validTo(),
                previousVersion, previousVersion + 1,
                cmd.reason(), cmd.requestedBy(), cmd.approvedBy(), now));

        outboxAppender.append(policy.pullEvents());
    }

    @Transactional
    public void changeStatus(ChangeStatusCommand cmd) {
        Instant now = Instant.now(clock);
        Policy policy = policyRepository.findCurrent(cmd.policyNo())
                .orElseThrow(() -> new PolicyNotFoundException(cmd.policyNo()));

        policy.changeStatus(cmd.toStatus(), cmd.effectiveFrom(), cmd.reason(),
                cmd.actorRef(), now);

        policyRepository.applyStatusChange(policy);
        outboxAppender.append(policy.pullEvents());
    }

    // ─── 커맨드 ────────────────────────────────────────────────────────────

    public record IssuePolicyCommand(
            PolicyNo policyNo, ProductRef product, String holderRef, InsuredInfo insured,
            PolicyPeriod period, LocalDate acceptedOn, LocalDate firstPremiumPaidOn,
            List<Coverage> coverages, List<Exclusion> exclusions,
            String applicationNo, String uwCaseNo) {
    }

    public record EndorseCoverageCommand(
            PolicyNo policyNo, CoverageCode coverageCode, CoverageTerms newTerms,
            Money newInsuredAmount, LocalDate effectiveFrom, String reason, String actorRef) {
    }

    public record CorrectExclusionCommand(
            PolicyNo policyNo, ExclusionId exclusionId, Exclusion replacement,
            String reason, String requestedBy, String approvedBy) {
    }

    public record ChangeStatusCommand(
            PolicyNo policyNo, PolicyStatus toStatus, LocalDate effectiveFrom,
            String reason, String actorRef) {
    }
}
