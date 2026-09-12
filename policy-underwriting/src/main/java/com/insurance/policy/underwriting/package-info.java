/**
 * 언더라이팅 룰 엔진.
 *
 * <p>7단계 파이프라인: 형식검증 → 인수가능성 → 고지평가 → 위험산정 → 조건결정 → 보유한도 → 라우팅
 *
 * <p>claims의 심사 엔진과 같은 원칙을 쓴다. 다만 오탐 비용의 방향이 반대다 —
 * 부당 부지급은 민원·분쟁이지만, 부당 거절은 영업 손실이다.
 * 그래서 claims보다 회부 기준이 덜 보수적이다. 청약 중 심사가 며칠 걸리면 고객이 이탈한다.
 *
 * <p>이 단계의 결정이 {@code Exclusion}(부담보)으로 계약에 새겨지고,
 * 몇 년 뒤 claims의 부지급 근거가 된다. 그래서 <b>왜 그 조건이 붙었는지</b>를
 * {@code UwRuleTrace}로 남긴다.
 *
 * <p>Phase 5에서 구현. 명세: {@code docs/design/03-underwriting.md}
 */
package com.insurance.policy.underwriting;
