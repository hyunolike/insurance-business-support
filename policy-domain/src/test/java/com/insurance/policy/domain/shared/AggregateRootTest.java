package com.insurance.policy.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AggregateRoot")
class AggregateRootTest {

    @Test
    @DisplayName("상태 전이가 이벤트를 기록한다")
    void shouldRecordEventOnTransition() {
        var aggregate = new TestAggregate();
        aggregate.doSomething();

        assertThat(aggregate.hasPendingEvents()).isTrue();
        assertThat(aggregate.peekEvents())
                .singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("test.happened"));
    }

    @Test
    @DisplayName("pullEvents는 꺼내고 비운다 — Outbox 중복 INSERT 방지")
    void shouldDrainOnPull() {
        var aggregate = new TestAggregate();
        aggregate.doSomething();
        aggregate.doSomething();

        assertThat(aggregate.pullEvents()).hasSize(2);
        assertThat(aggregate.pullEvents()).isEmpty();
        assertThat(aggregate.hasPendingEvents()).isFalse();
    }

    @Test
    @DisplayName("peekEvents는 비우지 않고, 반환된 목록은 수정할 수 없다")
    void shouldExposeImmutableView() {
        var aggregate = new TestAggregate();
        aggregate.doSomething();

        assertThat(aggregate.peekEvents()).hasSize(1);
        assertThat(aggregate.peekEvents()).hasSize(1);

        assertThatThrownBy(() -> aggregate.peekEvents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null 이벤트는 거부한다")
    void shouldRejectNullEvent() {
        var aggregate = new TestAggregate();
        assertThatThrownBy(aggregate::recordNull)
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("아무 일도 없었으면 이벤트도 없다")
    void shouldStartEmpty() {
        var aggregate = new TestAggregate();
        assertThat(aggregate.hasPendingEvents()).isFalse();
        assertThat(aggregate.pullEvents()).isEmpty();
    }

    // --- 테스트 픽스처 ---

    private static final class TestAggregate extends AggregateRoot {
        void doSomething() {
            record(new TestEvent(EventId.generate(), Instant.now()));
        }

        void recordNull() {
            record(null);
        }
    }

    private record TestEvent(EventId eventId, Instant occurredAt) implements DomainEvent {
        @Override
        public String eventType() {
            return "test.happened";
        }

        @Override
        public String aggregateId() {
            return "TEST-1";
        }

        @Override
        public java.util.Map<String, Object> payload() {
            return java.util.Map.of();
        }
    }
}
