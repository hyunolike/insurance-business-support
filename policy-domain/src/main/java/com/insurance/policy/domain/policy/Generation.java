package com.insurance.policy.domain.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 실손의료보험 세대.
 *
 * <p>자기부담 구조가 세대별로 근본적으로 다르므로 claims 심사 룰의 최상위 분기가 된다.
 *
 * <p><b>enum이 아니라 VO인 이유</b>: 제도 개정으로 새 세대가 추가되면 enum은 코드 변경과
 * 배포가 필요하다. 세대는 상품 마스터가 관리하는 <b>데이터</b>이고, 이 타입은 형식만 검증한다.
 * 세대별 요율·한도 역시 코드가 아니라 상품 마스터에 있다.
 */
public final class Generation {

    private static final Pattern FORMAT = Pattern.compile("^G[1-9]$");

    private final String code;

    private Generation(String code) {
        this.code = code;
    }

    public static Generation of(String code) {
        Objects.requireNonNull(code, "세대 코드는 필수입니다.");
        String normalized = code.toUpperCase();
        if (!FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("세대 코드 형식이 올바르지 않습니다(G1~G9): " + code);
        }
        return new Generation(normalized);
    }

    public String code() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Generation other && code.equals(other.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return code;
    }
}
