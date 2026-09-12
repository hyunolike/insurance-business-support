package com.insurance.policy.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("KcdRange")
class KcdRangeTest {

    @Test
    @DisplayName("범위 표기를 파싱한다")
    void shouldParseRange() {
        var range = KcdRange.parse("M40-M54");
        assertThat(range.from()).isEqualTo("M40");
        assertThat(range.to()).isEqualTo("M54");
        assertThat(range.expression()).isEqualTo("M40-M54");
    }

    @Test
    @DisplayName("단일 코드는 시작과 끝이 같은 범위다")
    void shouldParseSingleCode() {
        var range = KcdRange.parse("M51");
        assertThat(range.from()).isEqualTo("M51");
        assertThat(range.to()).isEqualTo("M51");
        assertThat(range.expression()).isEqualTo("M51");
    }

    @Test
    @DisplayName("소문자와 공백을 정규화한다")
    void shouldNormalize() {
        assertThat(KcdRange.parse("  m40-m54  ").expression()).isEqualTo("M40-M54");
    }

    @ParameterizedTest(name = "{0} 는 M40-M54 에 {1}")
    @DisplayName("범위 포함 판정")
    @CsvSource({
            "M40,   true",     // 경계 시작
            "M54,   true",     // 경계 끝
            "M51,   true",
            "M51.2, true",     // 세분류는 무시하고 M51로 판정
            "M39,   false",    // 경계 밖
            "M55,   false",
            "M99,   false",
            "K80,   false",    // 다른 알파벳
            "N51,   false"
    })
    void shouldJudgeContainment(String code, boolean expected) {
        assertThat(KcdRange.parse("M40-M54").contains(code)).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ 자릿수가 다른 코드를 사전순으로 비교하지 않는다")
    void shouldNotCompareLexicographically() {
        // 사전순 비교였다면 "M10" < "M9" 가 되어 범위 판정이 뒤집힌다.
        // 실제 KCD는 2자리 고정이라 M09 로 표기되지만, 비교 로직 자체가
        // 숫자 기반임을 고정해 둔다.
        var range = KcdRange.parse("M09-M11");

        assertThat(range.contains("M09")).isTrue();
        assertThat(range.contains("M10")).isTrue();
        assertThat(range.contains("M11")).isTrue();
        assertThat(range.contains("M12")).isFalse();
        assertThat(range.contains("M08")).isFalse();
    }

    @Test
    @DisplayName("실무 시나리오: 요추 추간판탈출증 수술 이력 → 척추 부담보 → 2년 뒤 청구 저촉")
    void shouldMatchRealWorldExclusionScenario() {
        // 언더라이팅이 부과한 척추 부담보
        var 척추부담보 = KcdRange.parse("M40-M54");

        // 2년 뒤 청구, 주상병 M51.2 (요추 추간판 장애)
        assertThat(척추부담보.contains("M51.2"))
                .as("claims의 R-POL-050이 이 판정으로 D-POL-004 부지급을 낸다")
                .isTrue();

        // 같은 피보험자의 다른 상병 — 저촉되지 않는다
        assertThat(척추부담보.contains("J20.9")).isFalse();   // 급성 기관지염
        assertThat(척추부담보.contains("K80.2")).isFalse();   // 담낭결석
    }

    @ParameterizedTest
    @DisplayName("형식이 틀리면 거부한다")
    @ValueSource(strings = {"", "M4", "MM40", "40-54", "M40-", "M40--M54", "M40-M54-M60", "척추"})
    void shouldRejectMalformedExpression(String expression) {
        assertThatThrownBy(() -> KcdRange.parse(expression))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시작이 끝보다 크면 거부한다")
    void shouldRejectInvertedRange() {
        assertThatThrownBy(() -> KcdRange.parse("M54-M40"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작이 끝보다");
    }

    @Test
    @DisplayName("null은 거부한다")
    void shouldRejectNull() {
        assertThatThrownBy(() -> KcdRange.parse(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KcdRange.parse("M40-M54").contains(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("조회 코드 형식이 틀리면 거부한다")
    void shouldRejectMalformedQueryCode() {
        var range = KcdRange.parse("M40-M54");
        assertThatThrownBy(() -> range.contains("INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("질병분류코드");
    }

    @Test
    @DisplayName("동등성과 표시")
    void shouldSupportEqualityAndToString() {
        var a = KcdRange.parse("M40-M54");
        var b = KcdRange.parse("m40-m54");
        var c = KcdRange.parse("K80-K87");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo("M40-M54");
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isEqualTo(a);
        assertThat(a).hasToString("M40-M54");
    }
}
