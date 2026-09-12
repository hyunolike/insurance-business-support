package com.insurance.policy.domain.shared.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Money")
class MoneyTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("0원 이상이면 생성된다")
        void shouldCreateWithNonNegativeAmount() {
            assertThat(Money.ofWon(0L)).isEqualTo(Money.ZERO);
            assertThat(Money.ofWon(124_000L).toWon()).isEqualTo(124_000L);
        }

        @Test
        @DisplayName("음수면 거부한다")
        void shouldRejectNegativeAmount() {
            assertThatThrownBy(() -> Money.ofWon(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("음수");
        }

        @Test
        @DisplayName("BigDecimal의 소수부는 거부한다 — 조용한 반올림은 금액 오차의 원인이 된다")
        void shouldRejectFractionalAmount() {
            assertThatThrownBy(() -> Money.ofWon(new BigDecimal("1000.50")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("소수부");
        }

        @Test
        @DisplayName("소수부가 0이면 허용한다 (1000.00 == 1000)")
        void shouldAcceptTrailingZeroScale() {
            assertThat(Money.ofWon(new BigDecimal("1000.00"))).isEqualTo(Money.ofWon(1000L));
        }

        @Test
        @DisplayName("null BigDecimal은 거부한다")
        void shouldRejectNullBigDecimal() {
            assertThatThrownBy(() -> Money.ofWon((BigDecimal) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("연산")
    class Arithmetic {

        @Test
        @DisplayName("더한다")
        void shouldAdd() {
            assertThat(Money.ofWon(40_000L).plus(Money.ofWon(84_000L)))
                    .isEqualTo(Money.ofWon(124_000L));
        }

        @Test
        @DisplayName("뺀다")
        void shouldSubtract() {
            assertThat(Money.ofWon(60_000L).minus(Money.ofWon(20_000L)))
                    .isEqualTo(Money.ofWon(40_000L));
        }

        @Test
        @DisplayName("뺄셈 결과가 음수면 거부한다")
        void shouldRejectNegativeSubtraction() {
            assertThatThrownBy(() -> Money.ofWon(10_000L).minus(Money.ofWon(20_000L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("음수");
        }

        @Test
        @DisplayName("minusToZero는 음수 대신 0을 반환한다 — 상계액이 원금을 넘는 경우")
        void shouldFloorToZero() {
            assertThat(Money.ofWon(10_000L).minusToZero(Money.ofWon(20_000L)))
                    .isEqualTo(Money.ZERO);
            assertThat(Money.ofWon(60_000L).minusToZero(Money.ofWon(20_000L)))
                    .isEqualTo(Money.ofWon(40_000L));
        }

        @ParameterizedTest(name = "{0}원 × {1} = {2}원")
        @DisplayName("비율 연산 결과는 원 미만 절사한다")
        @CsvSource({
                "60000, 0.20, 12000",
                "120000, 0.30, 36000",
                "12345, 0.20, 2469",
                "10001, 0.30, 3000",   // 3000.3 → 3000 (절사)
                "1, 0.99, 0"           // 0.99 → 0
        })
        void shouldMultiplyAndFloor(long base, String rate, long expected) {
            assertThat(Money.ofWon(base).multiply(new BigDecimal(rate)))
                    .isEqualTo(Money.ofWon(expected));
        }

        @Test
        @DisplayName("음수 비율은 거부한다")
        void shouldRejectNegativeRate() {
            assertThatThrownBy(() -> Money.ofWon(1000L).multiply(new BigDecimal("-0.1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("음수");
        }

        @Test
        @DisplayName("null 비율은 거부한다")
        void shouldRejectNullRate() {
            assertThatThrownBy(() -> Money.ofWon(1000L).multiply(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("overflow는 조용히 넘어가지 않는다")
        void shouldDetectOverflow() {
            Money huge = Money.ofWon(Long.MAX_VALUE);
            assertThatThrownBy(() -> huge.plus(Money.ofWon(1L)))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("비교")
    class Comparison {

        @Test
@DisplayName("최저보험료 보장은 max — 산출 보험료와 상품 최저보험료 중 큰 쪽")
        void shouldPickLarger() {
            assertThat(Money.max(Money.ofWon(8_000L), Money.ofWon(10_000L)))
                    .isEqualTo(Money.ofWon(10_000L));
            assertThat(Money.max(Money.ofWon(32_400L), Money.ofWon(10_000L)))
                    .isEqualTo(Money.ofWon(32_400L));
        }

        @Test
@DisplayName("보유한도 적용은 min — 신청 가입금액과 인수 가능 한도 중 작은 쪽")
        void shouldPickSmaller() {
            assertThat(Money.min(Money.ofWon(80_000_000L), Money.ofWon(50_000_000L)))
                    .isEqualTo(Money.ofWon(50_000_000L));
            assertThat(Money.min(Money.ofWon(30_000_000L), Money.ofWon(50_000_000L)))
                    .isEqualTo(Money.ofWon(30_000_000L));
        }

        @Test
        @DisplayName("같은 값이면 어느 쪽을 돌려줘도 동등하다")
        void shouldHandleEqualOperands() {
            Money a = Money.ofWon(1000L);
            Money b = Money.ofWon(1000L);
            assertThat(Money.max(a, b)).isEqualTo(a);
            assertThat(Money.min(a, b)).isEqualTo(a);
        }

        @Test
        @DisplayName("대소 비교")
        void shouldCompare() {
            Money small = Money.ofWon(100L);
            Money large = Money.ofWon(200L);

            assertThat(large.isGreaterThan(small)).isTrue();
            assertThat(small.isGreaterThan(large)).isFalse();
            assertThat(small.isLessThan(large)).isTrue();
            assertThat(large.isLessThan(small)).isFalse();
            assertThat(small).isLessThan(large);
        }

        @Test
        @DisplayName("0원 판별")
        void shouldDetectZero() {
            assertThat(Money.ZERO.isZero()).isTrue();
            assertThat(Money.ofWon(1L).isZero()).isFalse();
        }
    }

    @Nested
    @DisplayName("동등성")
    class Equality {

        @Test
        @DisplayName("같은 금액은 같다")
        void shouldBeEqualByValue() {
            assertThat(Money.ofWon(1000L))
                    .isEqualTo(Money.ofWon(1000L))
                    .hasSameHashCodeAs(Money.ofWon(1000L));
        }

        @Test
        @DisplayName("다른 금액·다른 타입·null과는 다르다")
        void shouldNotBeEqualToOthers() {
            Money money = Money.ofWon(1000L);
            assertThat(money).isNotEqualTo(Money.ofWon(2000L));
            assertThat(money).isNotEqualTo("1000");
            assertThat(money).isNotEqualTo(null);
            assertThat(money).isEqualTo(money);
        }

        @Test
        @DisplayName("사람이 읽을 수 있게 표시한다")
        void shouldRenderReadably() {
            assertThat(Money.ofWon(124_000L)).hasToString("124000원");
        }
    }

    @Test
    @DisplayName("할증 인수 보험료 산출: 기준 32,400원 + 50% 할증")
    void shouldApplyRating() {
        // 언더라이팅 결과 RATED(할증 50%)로 인수된 경우
        Money base = Money.ofWon(32_400L);
        Money extra = base.multiply(new BigDecimal("0.50"));

        assertThat(extra).isEqualTo(Money.ofWon(16_200L));
        assertThat(base.plus(extra)).isEqualTo(Money.ofWon(48_600L));
    }

    @Test
    @DisplayName("연체보험료 상계: 상계액이 환급액을 넘으면 0원")
    void shouldOffsetToZero() {
        Money refund = Money.ofWon(50_000L);
        Money arrears = Money.ofWon(97_200L);

        assertThat(refund.minusToZero(arrears))
                .as("환급액보다 연체액이 크면 환급은 0원이고 잔여 채권은 따로 관리한다")
                .isEqualTo(Money.ZERO);
    }
}
