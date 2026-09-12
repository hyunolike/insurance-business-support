/*
 * ─────────────────────────────────────────────────────────────────────────────
 * 의도적 중복 (Shared Kernel)
 *
 * claims-platform 에도 같은 형태의 타입이 있다. 공유 라이브러리로 빼지 않는 것은
 * 결정이지 게으름이 아니다 — 공유 모듈은 두 바운디드 컨텍스트를 컴파일 타임에
 * 다시 묶어버려서, 컨텍스트를 나눈 의미를 없앤다. 한쪽의 필요로 타입이 바뀌면
 * 다른 쪽이 원치 않는 변경을 강제로 받는다.
 *
 * 대신 두 레포가 주고받는 계약(스냅샷 응답, 이벤트 스키마)은 소비자 주도
 * 계약 테스트로 맞춘다. 결합 없이 안전성만 가져가는 방식이다.
 *
 * docs/design/01-context-map.md §8.1
 * ─────────────────────────────────────────────────────────────────────────────
 */
package com.insurance.policy.domain.shared.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 금액 (KRW).
 *
 * <p><b>원화에는 소수점이 없다.</b> 내부 표현은 {@code long}(원 단위 정수)이다.
 * v1은 {@code BigDecimal scale=2}를 썼는데, 이는 원화에 맞지 않고
 * "1원 미만이 존재한다"는 잘못된 모델을 코드에 새긴다.
 *
 * <p>통화 필드를 두지 않는다. 이 시스템은 KRW 단일 통화이며,
 * 쓰지 않을 다통화 개념을 미리 넣는 것은 비용만 남는다.
 *
 * <p>보험료 할증률 적용처럼 비율 연산이 필요한 경우 {@link #multiply(BigDecimal)}가
 * 중간값을 소수로 유지하되 <b>결과를 원 단위로 절사</b>해서 돌려준다.
 * 절사 시점과 방향을 한 곳에 모아두기 위함이다.
 *
 * @see docs/design/00-domain-glossary.md §6
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    private final long won;

    private Money(long won) {
        this.won = won;
    }

    public static Money ofWon(long won) {
        if (won < 0) {
            throw new IllegalArgumentException("금액은 음수가 될 수 없습니다: " + won);
        }
        return won == 0 ? ZERO : new Money(won);
    }

    /**
     * 외부 입력(JSON 등)에서 들어온 값을 변환한다.
     * 소수부가 있으면 거부한다 — 조용히 반올림하면 금액 오차의 원인을 추적할 수 없다.
     */
    public static Money ofWon(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("금액은 필수입니다.");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("원화 금액에 소수부를 지정할 수 없습니다: " + amount);
        }
        return ofWon(amount.longValueExact());
    }

    public Money plus(Money other) {
        return ofWon(Math.addExact(this.won, other.won));
    }

    /**
     * 뺄셈. 결과가 음수면 거부한다.
     * 상계액이 원금을 넘는 경우처럼 0으로 수렴해야 하는 계산에는
     * {@link #minusToZero(Money)}를 쓴다.
     */
    public Money minus(Money other) {
        long result = Math.subtractExact(this.won, other.won);
        if (result < 0) {
            throw new IllegalArgumentException(
                    "차감 결과가 음수입니다: " + this.won + " - " + other.won);
        }
        return ofWon(result);
    }

    /** 뺄셈하되 음수면 0. 감액·상계처럼 하한이 0인 계산에 쓴다. */
    public Money minusToZero(Money other) {
        long result = Math.subtractExact(this.won, other.won);
        return result < 0 ? ZERO : ofWon(result);
    }

    /** 비율 연산. 중간값은 소수로 계산하고 결과를 원 미만 절사한다. */
    public Money multiply(BigDecimal rate) {
        if (rate == null) {
            throw new IllegalArgumentException("비율은 필수입니다.");
        }
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("비율은 음수가 될 수 없습니다: " + rate);
        }
        BigDecimal result = BigDecimal.valueOf(won).multiply(rate).setScale(0, RoundingMode.FLOOR);
        return ofWon(result.longValueExact());
    }

    public static Money min(Money a, Money b) {
        return a.won <= b.won ? a : b;
    }

    public static Money max(Money a, Money b) {
        return a.won >= b.won ? a : b;
    }

    public boolean isZero() {
        return won == 0L;
    }

    public boolean isGreaterThan(Money other) {
        return this.won > other.won;
    }

    public boolean isLessThan(Money other) {
        return this.won < other.won;
    }

    /** 영속성·직렬화 경계에서만 쓴다. 계산에는 Money 연산을 쓸 것. */
    public long toWon() {
        return won;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.won, other.won);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return won == other.won;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(won);
    }

    @Override
    public String toString() {
        return won + "원";
    }
}
