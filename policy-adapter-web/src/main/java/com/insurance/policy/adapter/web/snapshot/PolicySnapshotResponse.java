package com.insurance.policy.adapter.web.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insurance.policy.domain.policy.Coverage;
import com.insurance.policy.domain.policy.Exclusion;
import com.insurance.policy.domain.policy.InstitutionGrade;
import com.insurance.policy.domain.policy.KcdRange;
import com.insurance.policy.domain.policy.PolicySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 스냅샷 API 응답 — <b>공표된 공용 계약(Open Host Service)</b>.
 *
 * <p>claims 전용 편의 DTO가 아니다. 다른 소비자가 붙을 수 있는 공개 인터페이스이므로
 * 다음 규칙을 지킨다:
 * <ul>
 *   <li>필드 추가는 자유, <b>삭제·의미 변경은 새 버전</b>({@code /api/v2})</li>
 *   <li>도메인 enum을 그대로 노출하지 않고 문자열로 공표한다 —
 *       내부 enum 변경이 API를 깨지 않게 하기 위함</li>
 *   <li><b>최소권한</b>: 고지사항·주소·연락처·모집인 정보·보험료 금액은 담지 않는다.
 *       claims가 심사에 필요한 것은 고지 내용이 아니라 그 결과물인 부담보 조건이다</li>
 * </ul>
 *
 * <p>계약 위반은 {@code contractTest}가 CI에서 잡는다.
 *
 * @see docs/design/05-api.md §1
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PolicySnapshotResponse(
        String policyNo,
        LocalDate asOf,
        Instant knownAt,
        int snapshotVersion,
        Product product,
        String policyStatusAsOf,
        Period policyPeriod,
        LocalDate effectiveDate,
        Insured insured,
        List<CoverageView> coverages,
        List<ExclusionView> exclusions,
        BenefitYearView benefitYear,
        String checksum
) {

    public static PolicySnapshotResponse from(PolicySnapshot snapshot) {
        return new PolicySnapshotResponse(
                snapshot.policyNo().value(),
                snapshot.asOf(),
                snapshot.knownAt(),
                snapshot.snapshotVersion(),
                new Product(snapshot.product().productCode(),
                        snapshot.product().productName(),
                        snapshot.product().generation().code()),
                snapshot.policyStatusAsOf().name(),
                new Period(snapshot.policyPeriod().from(), snapshot.policyPeriod().to()),
                snapshot.effectiveDate(),
                new Insured(snapshot.insured().insuredRef().value(),
                        snapshot.insured().birthYear(),
                        snapshot.insured().relationToHolder().name()),
                snapshot.coverages().stream().map(CoverageView::from).toList(),
                snapshot.exclusions().stream().map(ExclusionView::from).toList(),
                new BenefitYearView(snapshot.benefitYear().label(),
                        snapshot.benefitYear().from(),
                        snapshot.benefitYear().to()),
                null);   // 체크섬은 본문 확정 후 계산해 채운다
    }

    /** 체크섬을 채운 복사본. 계산은 본문(체크섬 제외)의 정규화 JSON으로 한다. */
    public PolicySnapshotResponse withChecksum(String checksum) {
        return new PolicySnapshotResponse(policyNo, asOf, knownAt, snapshotVersion, product,
                policyStatusAsOf, policyPeriod, effectiveDate, insured, coverages, exclusions,
                benefitYear, checksum);
    }

    public record Product(String productCode, String productName, String generation) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    /** 성명·연락처·주민등록번호를 담지 않는다. 심사에 불필요하다. */
    public record Insured(String insuredRef, int birthYear, String relationToHolder) {
    }

    public record BenefitYearView(String label, LocalDate from, LocalDate to) {
    }

    public record CoverageView(
            String coverageCode,
            String name,
            List<String> treatmentTypes,
            String benefitCategory,
            long insuredAmount,
            Terms terms,
            LocalDate waitingPeriodEnd
    ) {
        static CoverageView from(Coverage c) {
            return new CoverageView(
                    c.coverageCode().value(),
                    c.name(),
                    c.treatmentTypes().stream().map(Enum::name).sorted().toList(),
                    c.benefitCategory().name(),
                    c.insuredAmount().toWon(),
                    Terms.from(c),
                    c.waitingPeriodEnd());
        }

        /**
         * 담보 조건.
         *
         * <p>{@code coinsuranceRate}를 <b>문자열로</b> 내보낸다. 부동소수 표현으로
         * 왕복하면서 값이 미세하게 달라지면 체크섬이 흔들리고, 금액 계산에도 오차가 생긴다.
         */
        public record Terms(
                String coinsuranceRate,
                Long minDeductible,
                Map<String, Long> minDeductibleByGrade,
                Long annualLimit,
                Long perVisitLimit,
                Integer annualCountLimit
        ) {
            static Terms from(Coverage c) {
                var t = c.terms();
                Map<String, Long> byGrade = new TreeMap<>();
                for (Map.Entry<InstitutionGrade, Long> e : t.minDeductibleByGrade().entrySet()) {
                    byGrade.put(e.getKey().name(), e.getValue());
                }
                return new Terms(
                        normalizeRate(t.coinsuranceRate()),
                        t.minDeductible(),
                        byGrade.isEmpty() ? null : byGrade,
                        t.annualLimit(),
                        t.perVisitLimit(),
                        t.annualCountLimit());
            }

            /** 소수 2자리로 고정해 표기 흔들림을 없앤다. */
            private static String normalizeRate(BigDecimal rate) {
                return rate.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
            }
        }
    }

    /**
     * 부담보.
     *
     * <p>{@code kcdRanges}는 claims의 부지급 판정(D-POL-004)에 반드시 필요하므로 제공한다.
     * 건강정보를 추론할 수 있는 값이지만, 이것 없이는 심사가 불가능하다 —
     * 최소권한 원칙의 의도적 예외다.
     *
     * <p>{@code uwCaseNo}는 "왜 이 부담보가 붙었나"를 인수심사까지 역추적하는 경로다.
     */
    public record ExclusionView(
            String exclusionId,
            String type,
            String target,
            List<String> kcdRanges,
            LocalDate from,
            LocalDate until,
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
