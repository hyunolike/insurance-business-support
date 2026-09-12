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
package com.insurance.policy.domain.shared;

import java.time.Instant;

/**
 * 도메인 이벤트.
 *
 * <p>이벤트는 <b>이미 일어난 사실</b>이다. {@code ClaimApproved}(O) / {@code ApproveClaim}(X).
 * 발행 측은 누가 구독하는지 알지 못한다.
 *
 * <p>구현체는 반드시 불변이어야 하며, 민감정보(성명·계좌번호·KCD 코드·진단명)를
 * 필드로 갖지 않는다. 이벤트는 Kafka를 통해 컨텍스트 밖으로 나가므로
 * 한 번 실리면 통제할 수 없다.
 *
 * @see docs/design/04-events-and-integration.md
 */
public interface DomainEvent {

    /** 중복 제거 키. 소비자는 이 값으로 멱등성을 보장한다. */
    EventId eventId();

    /** Kafka 토픽 라우팅과 소비자 분기에 사용. 예: {@code claim.received} */
    String eventType();

    /** 이 이벤트가 속한 애그리거트의 대외 식별자. Kafka 파티션 키로 쓰인다. */
    String aggregateId();

    /** 사실이 발생한 시각. 발행 시각이 아니다. */
    Instant occurredAt();
}
