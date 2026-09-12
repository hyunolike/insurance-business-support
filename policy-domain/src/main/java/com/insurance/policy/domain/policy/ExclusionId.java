package com.insurance.policy.domain.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 부담보 논리 식별자.
 *
 * <p>같은 부담보의 여러 버전(정정 전/후)이 <b>같은 식별자를 공유한다.</b>
 * 그래야 "이 부담보가 언제 어떻게 바뀌었나"를 추적할 수 있다.
 *
 * <p>형식: {@code EXC-{연도}-{일련번호 6자리}}
 */
public final class ExclusionId {

    private static final Pattern FORMAT = Pattern.compile("^EXC-\\d{4}-\\d{6}$");

    private final String value;

    private ExclusionId(String value) {
        this.value = value;
    }

    public static ExclusionId of(String value) {
        Objects.requireNonNull(value, "부담보 식별자는 필수입니다.");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("부담보 식별자 형식이 올바르지 않습니다: " + value);
        }
        return new ExclusionId(value);
    }

    public static ExclusionId of(int year, long sequence) {
        if (sequence < 0 || sequence > 999_999L) {
            throw new IllegalArgumentException("일련번호가 범위를 벗어났습니다: " + sequence);
        }
        return new ExclusionId("EXC-%04d-%06d".formatted(year, sequence));
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExclusionId other && value.equals(other.value);
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
