package com.insurance.policy.domain.policy;

import java.util.Objects;

/**
 * 피보험자 식별자.
 *
 * <p><b>주민등록번호가 아니다.</b> CI(연계정보) 또는 내부 고객키만 담는다.
 * 주민등록번호는 이 시스템 어디에도 저장하지 않는다.
 */
public final class InsuredRef {

    private final String value;

    private InsuredRef(String value) {
        this.value = value;
    }

    public static InsuredRef of(String value) {
        Objects.requireNonNull(value, "피보험자 식별자는 필수입니다.");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("피보험자 식별자는 비어 있을 수 없습니다.");
        }
        if (trimmed.matches("\\d{6}-?[1-4]\\d{6}")) {
            throw new IllegalArgumentException(
                    "주민등록번호로 보이는 값은 식별자로 사용할 수 없습니다. CI 또는 내부 고객키를 쓰세요.");
        }
        return new InsuredRef(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InsuredRef other && value.equals(other.value);
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
