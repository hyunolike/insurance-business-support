package com.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurance.policy.application.port.out.OutboxAppender;
import com.insurance.policy.domain.shared.DomainEvent;
import com.insurance.policy.domain.shared.EventId;
import com.insurance.policy.support.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 적재의 트랜잭션 원자성을 검증한다.
 *
 * <p>{@code @Transactional} 안에서 외부로 직접 발행하면 커밋 전에 이벤트가 나간다.
 * 이 컨텍스트에서는 {@code policy.corrected}가 걸려 있어 특히 위험하다 —
 * 정정이 롤백됐는데 이벤트만 나가면 claims가 멀쩡한 청구를 재심사 큐에 쌓고,
 * 반대로 이벤트가 유실되면 claims는 틀린 근거로 계속 지급한다.
 */
@DisplayName("Outbox 적재")
class OutboxAppenderIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxAppender outboxAppender;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("커밋되면 이벤트가 남는다")
    void shouldPersistOnCommit() {
        TestEvent event = TestEvent.of("P2026-0001234");

        transactionTemplate.executeWithoutResult(status ->
                outboxAppender.append(List.of(event)));

        assertThat(countByEventId(event.eventId().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 롤백되면 이벤트도 사라진다 — 커밋 전 발행이었다면 외부로 이미 나갔을 것")
    void shouldVanishOnRollback() {
        TestEvent event = TestEvent.of("P2026-0001235");

        assertThatThrownBy(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    outboxAppender.append(List.of(event));
                    throw new IllegalStateException("정정 처리 도중 실패");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countByEventId(event.eventId().value()))
                .as("상태 변경과 이벤트는 원자적으로 함께 커밋되거나 함께 사라져야 한다")
                .isZero();
    }

    @Test
    @DisplayName("봉투에 이벤트 메타데이터가 담긴다")
    void shouldBuildEnvelope() {
        TestEvent event = TestEvent.of("P2026-0001236");

        transactionTemplate.executeWithoutResult(status ->
                outboxAppender.append(List.of(event)));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var row = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id, partition_key, status,
                       attempts, envelope::text AS envelope
                FROM outbox_event WHERE event_id = ?
                """, event.eventId().value());

        assertThat(row.get("event_type")).isEqualTo("policy.corrected");
        assertThat(row.get("aggregate_type")).isEqualTo("Policy");
        assertThat(row.get("aggregate_id")).isEqualTo("P2026-0001236");
        assertThat(row.get("partition_key"))
                .as("파티션 키는 애그리거트 ID다. 같은 계약의 이벤트 순서가 보장된다.")
                .isEqualTo("P2026-0001236");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat((String) row.get("envelope"))
                .contains("\"producer\":\"business-support\"")
                .contains("\"eventType\":\"policy.corrected\"");
    }

    @Test
    @DisplayName("빈 목록은 아무것도 적재하지 않는다")
    void shouldIgnoreEmptyInput() {
        long before = totalCount();

        transactionTemplate.executeWithoutResult(status -> {
            outboxAppender.append(List.of());
            outboxAppender.append(null);
        });

        assertThat(totalCount()).isEqualTo(before);
    }

    private int countByEventId(String eventId) {
        Integer count = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private long totalCount() {
        Long count = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM outbox_event", Long.class);
        return count == null ? 0L : count;
    }

    /** Phase 1에서 실제 PolicyCorrected로 대체된다. */
    record TestEvent(EventId eventId, String aggregateId, Instant occurredAt)
            implements DomainEvent {

        static TestEvent of(String policyNo) {
            return new TestEvent(EventId.generate(), policyNo, Instant.now());
        }

        @Override
        public String eventType() {
            return "policy.corrected";
        }

        @Override
        public Map<String, Object> payload() {
            return Map.of("policyNo", aggregateId);
        }
    }
}
