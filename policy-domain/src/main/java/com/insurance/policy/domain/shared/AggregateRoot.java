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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 애그리거트 루트.
 *
 * <p><b>이벤트는 애그리거트가 기록한다.</b> 서비스가 만들지 않는다.
 *
 * <p>서비스가 이벤트를 만들면, 상태를 바꾸는 새 경로가 생길 때마다 발행을 빠뜨릴 수 있다.
 * 상태 전이 메서드 안에서 {@link #record(DomainEvent)}를 호출하면 전이와 이벤트가
 * 같은 자리에 있어 누락이 구조적으로 불가능해진다.
 *
 * <p>이 컨텍스트에서 특히 중요한 것은 {@code policy.corrected}다.
 * 소급 정정이 발행을 빠뜨리면 claims가 틀린 근거로 계속 지급하게 된다.
 *
 * <p>애플리케이션 계층은 {@link #pullEvents()}로 이벤트를 꺼내
 * <b>같은 트랜잭션에서</b> Outbox 테이블에 INSERT한다.
 * 트랜잭션 안에서 외부(Kafka)로 직접 발행하지 않는다 — 롤백되면 되돌릴 수 없다.
 *
 * @see docs/design/02-domain-model.md §6
 */
public abstract class AggregateRoot {

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** 상태 전이 메서드 안에서 호출한다. */
    protected void record(DomainEvent event) {
        pendingEvents.add(Objects.requireNonNull(event, "도메인 이벤트는 null일 수 없습니다."));
    }

    /**
     * 기록된 이벤트를 꺼내고 비운다.
     *
     * <p>두 번 호출하면 두 번째는 빈 목록이다. 같은 이벤트가 Outbox에
     * 중복 INSERT되는 것을 막기 위한 의도된 동작이다.
     */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    /** 아직 꺼내지 않은 이벤트를 들여다본다. 주로 테스트용. */
    public List<DomainEvent> peekEvents() {
        return Collections.unmodifiableList(pendingEvents);
    }

    public boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }
}
