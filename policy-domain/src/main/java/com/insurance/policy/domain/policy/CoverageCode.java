package com.insurance.policy.domain.policy;

import java.util.Objects;

/** 담보 코드. claims가 보장 사용 원장(BenefitLedger)의 키로 쓴다. */
public final class CoverageCode {

    private final String value;

    private CoverageCode(String value) {
        this.value = value;
    }

    public static CoverageCode of(String value) {
        Objects.requireNonNull(value, "담보코드는 필수입니다.");
        String trimmed = value.trim().toUpperCase();
        if (trimmed.isEmpty() || trimmed.length() > 32) {
            throw new IllegalArgumentException("담보코드는 1~32자여야 합니다: " + value);
        }
        return new CoverageCode(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CoverageCode other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
