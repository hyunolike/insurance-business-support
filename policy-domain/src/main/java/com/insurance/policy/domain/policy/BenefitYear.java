package com.insurance.policy.domain.policy;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 보장연도 — 한도가 리셋되는 주기.
 *
 * <p><b>계약 응당일 기준이지 달력연도가 아니다.</b>
 * 2026-03-01 계약이면 보장연도는 {@code 2026-03-01 ~ 2027-03-01}이다.
 * 달력연도로 계산하면 한도가 틀어진다 — v1 설계에는 이 개념 자체가 없었다.
 *
 * <p>claims의 {@code BenefitLedger}가 이 값을 키로 한도 사용량을 누적한다.
 */
public record BenefitYear(String label, LocalDate from, LocalDate to) {

    public BenefitYear {
        Objects.requireNonNull(label, "보장연도 라벨은 필수입니다.");
        Objects.requireNonNull(from, "보장연도 시작일은 필수입니다.");
        Objects.requireNonNull(to, "보장연도 종료일은 필수입니다.");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "보장연도 시작일은 종료일보다 앞서야 합니다: %s ~ %s".formatted(from, to));
        }
    }

    /**
     * 계약 응당일 기준으로 {@code asOf}가 속한 보장연도를 계산한다.
     *
     * @param effectiveDate 책임개시일 (응당일의 기준)
     * @param asOf          기준일 (보통 사고일)
     */
    public static BenefitYear of(LocalDate effectiveDate, LocalDate asOf) {
        Objects.requireNonNull(effectiveDate, "책임개시일은 필수입니다.");
        Objects.requireNonNull(asOf, "기준일은 필수입니다.");
        if (asOf.isBefore(effectiveDate)) {
            throw new IllegalArgumentException(
                    "기준일이 책임개시일보다 앞섭니다: asOf=%s, effectiveDate=%s"
                            .formatted(asOf, effectiveDate));
        }

        // 응당일을 asOf 이하에서 가장 가까운 지점까지 1년씩 전진시킨다.
        // 2월 29일 계약처럼 해당 날짜가 없는 해는 plusYears가 2월 28일로 맞춰 준다.
        int elapsed = asOf.getYear() - effectiveDate.getYear();
        LocalDate anniversary = effectiveDate.plusYears(elapsed);
        if (anniversary.isAfter(asOf)) {
            anniversary = effectiveDate.plusYears(elapsed - 1L);
            elapsed -= 1;
        }
        LocalDate next = effectiveDate.plusYears(elapsed + 1L);

        return new BenefitYear("%d년차(%s~)".formatted(elapsed + 1, anniversary),
                anniversary, next);
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(from) && date.isBefore(to);
    }
}
