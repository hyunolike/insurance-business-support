package com.insurance.policy.domain.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 계약 스냅샷 — 특정 시점의 계약 상태를 굳힌 불변 값.
 *
 * <p>claims가 청구 접수 시점에 이것을 받아 <b>불변 복제본으로 저장</b>하고,
 * 이후 모든 심사·재심사는 저장된 스냅샷만 본다. 계약이 나중에 바뀌어도
 * 심사 근거가 보존되는 것이 그 구조의 목적이다.
 *
 * <p>두 시간축으로 지정된다:
 * <ul>
 *   <li>{@code asOf} — 유효시간. 보통 사고일. "그때 계약은 어땠나"</li>
 *   <li>{@code knownAt} — 기록시간. "그 답을 우리는 언제부터 알았나"</li>
 * </ul>
 *
 * <p><b>같은 {@code (policyNo, asOf, knownAt)}은 영원히 같은 결과여야 한다.</b>
 * 이것이 이 시스템의 절대 요구사항이고, 깨지면 두 레포를 나눈 설계 전체가 의미를 잃는다.
 */
public record PolicySnapshot(
        PolicyNo policyNo,
        LocalDate asOf,
        Instant knownAt,
        int snapshotVersion,
        ProductRef product,
        PolicyStatus policyStatusAsOf,
        PolicyPeriod policyPeriod,
        LocalDate effectiveDate,
        InsuredInfo insured,
        List<Coverage> coverages,
        List<Exclusion> exclusions,
        BenefitYear benefitYear
) {

    public PolicySnapshot {
        Objects.requireNonNull(policyNo, "계약번호는 필수입니다.");
        Objects.requireNonNull(asOf, "기준일(asOf)은 필수입니다.");
        Objects.requireNonNull(knownAt, "기록시점(knownAt)은 필수입니다.");
        Objects.requireNonNull(product, "상품 정보는 필수입니다.");
        Objects.requireNonNull(policyStatusAsOf, "기준일 시점 계약상태는 필수입니다.");
        Objects.requireNonNull(policyPeriod, "보험기간은 필수입니다.");
        Objects.requireNonNull(effectiveDate, "책임개시일은 필수입니다.");
        Objects.requireNonNull(insured, "피보험자 정보는 필수입니다.");
        Objects.requireNonNull(benefitYear, "보장연도는 필수입니다.");
        coverages = List.copyOf(Objects.requireNonNull(coverages, "담보 목록은 필수입니다."));
        exclusions = List.copyOf(Objects.requireNonNull(exclusions, "부담보 목록은 필수입니다."));
        if (snapshotVersion < 1) {
            throw new IllegalArgumentException("스냅샷 버전은 1 이상이어야 합니다: " + snapshotVersion);
        }
    }

    /**
     * 이 시점에 사고가 계약 기간 안에서 발생했는가.
     *
     * <p>편의 판정일 뿐이며, <b>최종 보상 판정은 claims의 책임</b>이다.
     * 이 컨텍스트는 사실만 제공하고 지급 여부를 결정하지 않는다.
     */
    public boolean withinPolicyPeriod() {
        return policyPeriod.covers(asOf) && !asOf.isBefore(effectiveDate);
    }

    /** 주어진 질병분류코드에 저촉되는 부담보. claims가 D-POL-004 판정에 쓴다. */
    public List<Exclusion> exclusionsMatching(String kcdCode) {
        return exclusions.stream().filter(e -> e.covers(kcdCode)).toList();
    }

    public List<Coverage> coveragesFor(TreatmentType treatmentType) {
        return coverages.stream().filter(c -> c.appliesTo(treatmentType)).toList();
    }
}
