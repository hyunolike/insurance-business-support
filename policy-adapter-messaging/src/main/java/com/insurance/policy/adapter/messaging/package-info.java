/**
 * Kafka 어댑터와 Outbox 릴레이.
 *
 * <p>발행 이벤트 중 {@code policy.corrected}가 특별하다. 나머지는 "오늘부터 이렇게 됐다"는
 * 통보라 확정된 심사에 영향을 주지 않지만, 정정은 "과거의 사실이 틀렸었다"는 선언이라
 * 그 사실에 근거한 claims의 심사가 전부 흔들린다.
 *
 * <p>claims의 {@code claim.paid}도 구독하지만 <b>통계 적재만</b> 한다.
 * 계약 상태를 자동으로 바꾸지 않는다 — 바꾸면 청구 처리 장애가 계약 업무 마비로 번진다.
 *
 * <p>Phase 1에서 구현. 명세: {@code docs/design/04-events-and-integration.md}
 */
package com.insurance.policy.adapter.messaging;
