package com.insurance.policy.domain.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 계약번호. 대외 식별자.
 *
 * <p>형식: {@code P{연도}-{일련번호 7자리}} — 예) {@code P2026-0001234}
 *
 * <p>일련번호는 DB 시퀀스로 발급한다. 난수를 쓰지 않는 이유는 claims의 청구번호와 같다 —
 * 충돌 가능성이 있고, 정렬해도 발급 순서가 아니며, 재시도 시 다른 번호가 나와 멱등성이 깨진다.
 */
public final class PolicyNo {

    private static final Pattern FORMAT = Pattern.compile("^P\\d{4}-\\d{7}$");

    private final String value;

    private PolicyNo(String value) {
        this.value = value;
    }

    public static PolicyNo of(String value) {
        Objects.requireNonNull(value, "계약번호는 필수입니다.");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("계약번호 형식이 올바르지 않습니다: " + value);
        }
        return new PolicyNo(value);
    }

    public static PolicyNo of(int year, long sequence) {
        if (year < 1900 || year > 9999) {
            throw new IllegalArgumentException("연도가 범위를 벗어났습니다: " + year);
        }
        if (sequence < 0 || sequence > 9_999_999L) {
            throw new IllegalArgumentException("일련번호가 범위를 벗어났습니다: " + sequence);
        }
        return new PolicyNo("P%04d-%07d".formatted(year, sequence));
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PolicyNo other && value.equals(other.value);
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
