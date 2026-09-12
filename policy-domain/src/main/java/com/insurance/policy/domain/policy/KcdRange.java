package com.insurance.policy.domain.policy;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KCD(한국표준질병사인분류) 코드 범위.
 *
 * <p>부담보 조건이 이 범위로 표현되고, 몇 년 뒤 claims의 부지급 근거가 된다.
 * 예) 요추 추간판탈출증 수술 이력 → {@code M40-M54} 5년 부담보
 * → 2년 뒤 주상병 {@code M51.2} 청구가 이 범위에 걸려 {@code D-POL-004} 부지급.
 *
 * <p><b>범위 비교는 (알파벳, 숫자) 쌍으로 한다.</b> 문자열 사전순으로 비교하면 안 된다 —
 * {@code "M9"} 와 {@code "M10"} 을 문자열로 비교하면 {@code "M10" < "M9"} 가 되어
 * 범위 판정이 뒤집힌다. 자릿수 정규화 없이 사전순 비교를 쓰는 것이 이 영역의 고전적 버그다.
 *
 * <p>세분류(소수점 이하)는 비교에서 제외한다. {@code M51.2} 는 {@code M51} 로 보고 판정한다.
 * 부담보는 통상 세분류 단위가 아니라 3자리 분류 단위로 부과되기 때문이다.
 */
public final class KcdRange {

    /** 단일 코드: 대문자 1자 + 숫자 2자, 선택적 세분류 (예: M51, M51.2) */
    private static final Pattern CODE = Pattern.compile("^([A-Z])(\\d{2})(?:\\.(\\d{1,2}))?$");
    /** 범위 표기: M40-M54 또는 단일 코드 M51 */
    private static final Pattern RANGE = Pattern.compile("^([A-Z]\\d{2})(?:-([A-Z]\\d{2}))?$");

    private final String from;
    private final String to;

    private KcdRange(String from, String to) {
        this.from = from;
        this.to = to;
    }

    /**
     * {@code "M40-M54"} 또는 단일 코드 {@code "M51"} 을 파싱한다.
     */
    public static KcdRange parse(String expression) {
        Objects.requireNonNull(expression, "KCD 범위는 필수입니다.");
        String normalized = expression.trim().toUpperCase();
        Matcher m = RANGE.matcher(normalized);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "KCD 범위 형식이 올바르지 않습니다(예: M40-M54, M51): " + expression);
        }
        String from = m.group(1);
        String to = m.group(2) != null ? m.group(2) : from;

        if (compareCodes(from, to) > 0) {
            throw new IllegalArgumentException(
                    "KCD 범위의 시작이 끝보다 큽니다: " + expression);
        }
        return new KcdRange(from, to);
    }

    /**
     * 주어진 질병분류코드가 이 범위에 포함되는가.
     *
     * @param kcdCode 세분류를 포함할 수 있다 (예: {@code M51.2}). 세분류는 무시하고 판정한다.
     */
    public boolean contains(String kcdCode) {
        Objects.requireNonNull(kcdCode, "질병분류코드는 필수입니다.");
        String category = toCategory(kcdCode);
        return compareCodes(from, category) <= 0 && compareCodes(category, to) <= 0;
    }

    /** 세분류를 떼고 3자리 분류만 남긴다. {@code M51.2 → M51} */
    private static String toCategory(String kcdCode) {
        Matcher m = CODE.matcher(kcdCode.trim().toUpperCase());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "질병분류코드 형식이 올바르지 않습니다(예: M51, M51.2): " + kcdCode);
        }
        return m.group(1) + m.group(2);
    }

    /**
     * (알파벳, 숫자) 쌍으로 비교한다.
     *
     * <p>입력이 항상 '대문자 1자 + 숫자 2자'로 정규화되어 있어 자릿수가 고정이므로
     * 숫자 부분도 안전하게 정수로 비교할 수 있다.
     */
    private static int compareCodes(String a, String b) {
        int alpha = Character.compare(a.charAt(0), b.charAt(0));
        if (alpha != 0) {
            return alpha;
        }
        return Integer.compare(Integer.parseInt(a.substring(1)), Integer.parseInt(b.substring(1)));
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    /** 저장·전송용 표기. 단일 코드면 {@code "M51"}, 범위면 {@code "M40-M54"}. */
    public String expression() {
        return from.equals(to) ? from : from + "-" + to;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KcdRange other && from.equals(other.from) && to.equals(other.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }

    @Override
    public String toString() {
        return expression();
    }
}
