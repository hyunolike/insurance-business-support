package com.insurance.policy.adapter.web.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insurance.policy.application.policy.PolicyQueryService.PolicyDetail;
import com.insurance.policy.domain.policy.Coverage;
import com.insurance.policy.domain.policy.Exclusion;
import com.insurance.policy.domain.policy.KcdRange;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약 상세 — <b>사내 운영용</b> 응답.
 *
 * <p>스냅샷 응답과 일부러 타입을 나눴다. 하나로 합치면 운영 편의로 필드를 하나 더 넣을 때마다
 * claims의 공용 계약이 흔들린다. 이쪽은 자유롭게 바뀌어도 되는 내부 표현이다.
 *
 * <p>{@code applicationNo}/{@code uwCaseNo}가 여기에만 있는 이유:
 * "왜 이 부담보가 붙었나"를 인수심사까지 역추적하는 경로인데, 운영자에게는 필요하고
 * claims의 심사에는 불필요하다.
 *
 * <p>여전히 담지 않는 것: 성명·주민등록번호·주소·연락처·고지사항 상세.
 * 운영용이라고 해서 최소권한이 사라지지는 않는다. 고지사항은 별도 엔드포인트에서
 * {@code ROLE_UNDERWRITER} + {@code audit_log} 기록과 함께 다룬다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PolicyDetailResponse(
        String policyNo,
        LocalDate asOf,
        Instant knownAt,
        int snapshotVersion,
        String statusAsOf,
        Product product,
        Period policyPeriod,
        LocalDate effectiveDate,
        String holderRef,
        Insured insured,
        String applicationNo,
        String uwCaseNo,
        List<CoverageView> coverages,
        List<ExclusionView> exclusions
) {

    public static PolicyDetailResponse from(PolicyDetail detail) {
        var policy = detail.policy();
        var snapshot = detail.snapshot();

        return new PolicyDetailResponse(
                policy.policyNo().value(),
                detail.asOf(),
                detail.knownAt(),
                snapshot.snapshotVersion(),
                snapshot.policyStatusAsOf().name(),
                new Product(policy.product().productCode(),
                        policy.product().productName(),
                        policy.product().generation().code()),
                new Period(policy.period().from(), policy.period().to()),
                policy.effectiveDate(),
                policy.holderRef(),
                new Insured(policy.insured().insuredRef().value(),
                        policy.insured().birthYear(),
                        policy.insured().relationToHolder().name()),
                policy.applicationNo(),
                policy.uwCaseNo(),
                snapshot.coverages().stream().map(CoverageView::from).toList(),
                snapshot.exclusions().stream().map(ExclusionView::from).toList());
    }

    public record Product(String productCode, String productName, String generation) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    public record Insured(String insuredRef, int birthYear, String relationToHolder) {
    }

    public record CoverageView(
            String coverageCode,
            String name,
            String benefitCategory,
            long insuredAmount,
            String coinsuranceRate,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        static CoverageView from(Coverage c) {
            return new CoverageView(
                    c.coverageCode().value(),
                    c.name(),
                    c.benefitCategory().name(),
                    c.insuredAmount().toWon(),
                    // 스냅샷 응답과 같은 이유로 문자열이다 — 부동소수 왕복으로 값이 흔들리지 않게.
                    c.terms().coinsuranceRate()
                            .setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                    c.validFrom(),
                    c.validTo());
        }
    }

    public record ExclusionView(
            String exclusionId,
            String type,
            String target,
            List<String> kcdRanges,
            LocalDate validFrom,
            LocalDate validTo,
            String reason,
            String uwCaseNo
    ) {
        static ExclusionView from(Exclusion e) {
            return new ExclusionView(
                    e.exclusionId().value(),
                    e.type().name(),
                    e.target(),
                    e.kcdRanges().stream().map(KcdRange::expression).sorted().toList(),
                    e.validFrom(),
                    e.validTo(),
                    e.reason(),
                    e.uwCaseNo());
        }
    }
}
