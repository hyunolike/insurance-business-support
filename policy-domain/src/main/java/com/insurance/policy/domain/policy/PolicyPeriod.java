package com.insurance.policy.domain.policy;

import java.time.LocalDate;
import java.util.Objects;

/** 보험기간. 종료일은 배타적이다 — 만기일 당일 사고는 보험기간 밖이다. */
public record PolicyPeriod(LocalDate from, LocalDate to) {

    public PolicyPeriod {
        Objects.requireNonNull(from, "보험기간 시작일은 필수입니다.");
        Objects.requireNonNull(to, "보험기간 종료일은 필수입니다.");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "보험기간 시작일은 종료일보다 앞서야 합니다: %s ~ %s".formatted(from, to));
        }
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(from) && date.isBefore(to);
    }
}
