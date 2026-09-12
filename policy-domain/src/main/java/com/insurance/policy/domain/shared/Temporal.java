package com.insurance.policy.domain.shared;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 두 개의 시간축을 가진 사실(fact).
 *
 * <p>이 인터페이스가 이 저장소 전체의 설계를 규정한다.
 *
 * <table border="1">
 *   <caption>두 시간축</caption>
 *   <tr><th>축</th><th>필드</th><th>대답하는 질문</th></tr>
 *   <tr>
 *     <td>유효시간 (valid time)</td>
 *     <td>{@code validFrom} / {@code validTo}</td>
 *     <td>"2026-03-14에 이 계약은 어떤 상태였나?"</td>
 *   </tr>
 *   <tr>
 *     <td>기록시간 (transaction time)</td>
 *     <td>{@code recordedAt} / {@code supersededAt}</td>
 *     <td>"그 답을 우리는 언제부터 알고 있었나?"</td>
 *   </tr>
 * </table>
 *
 * <h2>왜 두 축이 필요한가</h2>
 * <pre>
 * 2026-01-01  계약 체결, 척추 부담보 (기록: 2026-01-01)
 * 2026-03-14  사고 발생
 * 2026-04-02  청구 접수 → 부담보 저촉 → 부지급
 * 2026-05-20  착오 발견: 부담보는 잘못 입력된 것 → 소급 정정
 * 2026-06-01  민원 → 재심사
 * </pre>
 *
 * <p>유효시간만 있으면 6월에 조회할 때 "부담보 없음"이 나온다.
 * 그러면 <b>4월의 부지급 판단을 설명할 수 없고</b>, 우리가 틀렸다는 것조차 증명하지 못한다.
 *
 * <p>기록시간까지 있으면:
 * <ul>
 *   <li>{@code asOf=2026-03-14, knownAt=2026-04-02} → 부담보 있음 (그때 알던 것)</li>
 *   <li>{@code asOf=2026-03-14, knownAt=now} → 부담보 없음 (지금 아는 진실)</li>
 * </ul>
 *
 * <h2>불변 규칙</h2>
 * 이 인터페이스를 구현하는 엔티티는 <b>기존 행을 UPDATE하지 않는다.</b>
 * <ul>
 *   <li>변경(Endorsement) = 기존 구간 종료 + 새 행</li>
 *   <li>정정(Correction) = {@code supersededAt} 설정(유일하게 허용되는 UPDATE) + 새 행</li>
 * </ul>
 * DB 트리거와 {@code EXCLUDE USING gist} 제약이 이를 강제한다.
 *
 * @see docs/design/00-domain-glossary.md §3
 */
public interface Temporal {

    /** 현실에서 유효해진 날 (포함). */
    LocalDate validFrom();

    /** 현실에서 유효가 끝나는 날 (배타적). */
    LocalDate validTo();

    /** 시스템에 기록된 시점. */
    Instant recordedAt();

    /** 이 기록이 정정으로 대체된 시점. {@code null}이면 아직 유효한 기록. */
    Instant supersededAt();

    /**
     * {@code asOf} 시점에 유효했고, {@code knownAt} 시점에 우리가 알고 있던 사실인가.
     *
     * @param asOf    유효시간 기준일 (보통 사고일)
     * @param knownAt 기록시간 기준. 현재 시각을 주면 "지금 아는 진실"
     */
    default boolean isEffectiveOn(LocalDate asOf, Instant knownAt) {
        return isValidOn(asOf) && wasKnownAt(knownAt);
    }

    /** 유효시간 축만 검사한다. */
    default boolean isValidOn(LocalDate asOf) {
        return !asOf.isBefore(validFrom()) && asOf.isBefore(validTo());
    }

    /** 기록시간 축만 검사한다. */
    default boolean wasKnownAt(Instant knownAt) {
        if (knownAt.isBefore(recordedAt())) {
            return false;   // 아직 기록되기 전
        }
        Instant superseded = supersededAt();
        return superseded == null || knownAt.isBefore(superseded);
    }

    /** 정정으로 대체되지 않은 현행 기록인가. */
    default boolean isCurrentRecord() {
        return supersededAt() == null;
    }
}
