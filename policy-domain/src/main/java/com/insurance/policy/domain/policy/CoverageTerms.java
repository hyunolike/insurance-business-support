package com.insurance.policy.domain.policy;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 담보 조건. claims의 금액 산출에 그대로 쓰이는 파라미터다.
 *
 * <p>자기부담금 = {@code max(대상금액 × coinsuranceRate, 최소공제금액)}
 *
 * <p>입원은 최소공제금액이 없고 정률만 적용된다 → {@code minDeductible}, {@code minDeductibleByGrade} 모두 null.
 * 통원은 요양기관 종별로 최소공제금액이 다르다 → {@code minDeductibleByGrade} 사용.
 *
 * <p>여기 담기는 값은 <b>이 계약의 조건</b>이다. 표준 요율은 claims의 {@code ruleset_parameter}에
 * 있고, 계약별 조건이 표준과 다를 수 있으므로 스냅샷 값이 항상 우선한다.
 */
public record CoverageTerms(
        BigDecimal coinsuranceRate,
        Long minDeductible,
        Map<InstitutionGrade, Long> minDeductibleByGrade,
        Long annualLimit,
        Long perVisitLimit,
        Integer annualCountLimit
) {

    public CoverageTerms {
        Objects.requireNonNull(coinsuranceRate, "자기부담률은 필수입니다.");
        if (coinsuranceRate.signum() < 0 || coinsuranceRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("자기부담률은 0~1 사이여야 합니다: " + coinsuranceRate);
        }
        if (minDeductible != null && minDeductible < 0) {
            throw new IllegalArgumentException("최소공제금액은 음수가 될 수 없습니다: " + minDeductible);
        }
        if (minDeductible != null && minDeductibleByGrade != null
                && !minDeductibleByGrade.isEmpty()) {
            throw new IllegalArgumentException(
                    "최소공제금액을 정액과 종별표로 동시에 지정할 수 없습니다. 하나만 쓰세요.");
        }
        minDeductibleByGrade = minDeductibleByGrade == null || minDeductibleByGrade.isEmpty()
                ? Map.of()
                : Map.copyOf(new EnumMap<>(minDeductibleByGrade));
        if (annualLimit != null && annualLimit < 0) {
            throw new IllegalArgumentException("연간 한도는 음수가 될 수 없습니다: " + annualLimit);
        }
        if (annualCountLimit != null && annualCountLimit < 0) {
            throw new IllegalArgumentException("연간 횟수 한도는 음수가 될 수 없습니다: " + annualCountLimit);
        }
    }

    /** 입원용 — 최소공제금액 없이 정률만 */
    public static CoverageTerms inpatient(BigDecimal rate, Long annualLimit) {
        return new CoverageTerms(rate, null, null, annualLimit, null, null);
    }

    /** 통원용 — 요양기관 종별 최소공제금액 */
    public static CoverageTerms outpatient(BigDecimal rate,
                                           Map<InstitutionGrade, Long> byGrade,
                                           Long perVisitLimit,
                                           Integer annualCountLimit) {
        return new CoverageTerms(rate, null, byGrade, null, perVisitLimit, annualCountLimit);
    }

    /** 정액 최소공제금액 (비급여 통원, 처방조제 등) */
    public static CoverageTerms flatDeductible(BigDecimal rate,
                                               Long minDeductible,
                                               Long perVisitLimit,
                                               Integer annualCountLimit) {
        return new CoverageTerms(rate, minDeductible, null, null, perVisitLimit, annualCountLimit);
    }

    /** 해당 요양기관 종별에 적용되는 최소공제금액. 없으면 null(=미적용). */
    public Long minDeductibleFor(InstitutionGrade grade) {
        if (minDeductible != null) {
            return minDeductible;
        }
        return grade == null ? null : minDeductibleByGrade.get(grade);
    }
}
