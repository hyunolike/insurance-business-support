package com.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurance.policy.adapter.messaging.outbox.OutboxRelay;
import com.insurance.policy.application.port.out.EventPublisher;
import com.insurance.policy.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Outbox 릴레이 — 실제 PostgreSQL에서.
 *
 * <p>Kafka는 띄우지 않는다. {@link EventPublisher}를 기록용 구현으로 바꿔 끼우면
 * 릴레이의 <b>순서·재시도·데드레터 판단</b>을 브로커 없이 전부 검증할 수 있다.
 * Kafka 연동 자체(직렬화, acks)는 {@code KafkaEventPublisher}의 책임이고
 * 여기서 볼 것이 아니다.
 *
 * <p>스케줄러는 켜지 않고 {@code relayOnce()}를 직접 부른다. 배경에서 도는 폴링에
 * 단언을 맞추면 타이밍에 따라 흔들리는 테스트가 된다.
 */
@Import(OutboxRelayIntegrationTest.RecordingPublisherConfig.class)
@TestPropertySource(properties = {
        "policy.outbox.relay.enabled=true",
        // Kafka 발행기 대신 아래 기록용 구현을 쓴다. kafka 로 두면
        // KafkaTemplate 을 요구해 컨텍스트 로딩이 실패한다.
        "policy.outbox.relay.publisher=recording",
        // 스케줄러가 배경에서 돌지 않게 한다. 검증은 relayOnce() 직접 호출로.
        "policy.outbox.relay.initial-delay=3600000",
        "policy.outbox.relay.max-attempts=3"
})
@DisplayName("Outbox 릴레이")
class OutboxRelayIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private RecordingEventPublisher publisher;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE outbox_event RESTART IDENTITY CASCADE");
        publisher.reset();
    }

    // ─── 적재 헬퍼 ────────────────────────────────────────────────────────

    /** Outbox에 직접 넣는다. 애그리거트 저장 경로는 다른 테스트가 본다. */
    private void 적재(String eventId, String eventType, String aggregateId) {
        jdbc.update("""
                INSERT INTO outbox_event (event_id, event_type, event_version, aggregate_type,
                        aggregate_id, partition_key, envelope, occurred_at, status)
                VALUES (?, ?, 1, 'Policy', ?, ?, ?::jsonb, NOW(), 'PENDING')
                """, eventId, eventType, aggregateId, aggregateId,
                "{\"eventId\":\"%s\",\"payload\":{\"policyNo\":\"%s\"}}"
                        .formatted(eventId, aggregateId));
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private int attemptsOf(String eventId) {
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
        return attempts == null ? 0 : attempts;
    }

    @Nested
    @DisplayName("발행")
    class Publishing {

        @Test
        @DisplayName("PENDING 이벤트를 발행하고 PUBLISHED로 표시한다")
        void shouldPublishAndMark() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");

            int published = relay.relayOnce();

            assertThat(published).isEqualTo(1);
            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("PUBLISHED");
            assertThat(publisher.sent()).singleElement().satisfies(sent -> {
                assertThat(sent.topic()).isEqualTo("policy.policy-events.v1");
                assertThat(sent.partitionKey())
                        .as("파티션 키는 계약번호다 — 같은 계약의 순서 보장 단위")
                        .isEqualTo("P2026-0000001");
                assertThat(sent.envelope()).contains("P2026-0000001");
            });
        }

        @Test
        @DisplayName("발행한 이벤트를 다시 발행하지 않는다")
        void shouldNotRepublish() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");

            relay.relayOnce();
            relay.relayOnce();

            assertThat(publisher.sent()).hasSize(1);
        }

        @Test
        @DisplayName("보낼 것이 없으면 아무 일도 하지 않는다")
        void shouldDoNothingWhenEmpty() {
            assertThat(relay.relayOnce()).isZero();
            assertThat(publisher.sent()).isEmpty();
        }

        @Test
        @DisplayName("서로 다른 계약은 한 배치에서 함께 나간다")
        void shouldPublishDifferentAggregatesTogether() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            적재("01BBBBBBBBBBBBBBBBBBBBBBBB", "policy.issued", "P2026-0000002");
            적재("01CCCCCCCCCCCCCCCCCCCCCCCC", "policy.issued", "P2026-0000003");

            assertThat(relay.relayOnce()).isEqualTo(3);
        }

        @Test
        @DisplayName("토픽이 등록되지 않은 이벤트는 발행하지 않고 실패로 남긴다")
        void shouldFailUnknownEventType() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.unknown_thing", "P2026-0000001");

            assertThat(relay.relayOnce()).isZero();

            assertThat(publisher.sent())
                    .as("모르는 토픽으로 내보내면 아무도 구독하지 않는 곳으로 사라진다")
                    .isEmpty();
            assertThat(attemptsOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("★ 애그리거트 내 순서")
    class Ordering {

        @Test
        @DisplayName("같은 계약의 이벤트는 한 번에 하나씩, 순서대로 나간다")
        void shouldPublishOneEventPerAggregatePerCycle() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            적재("01BBBBBBBBBBBBBBBBBBBBBBBB", "policy.endorsed", "P2026-0000001");
            적재("01CCCCCCCCCCCCCCCCCCCCCCCC", "policy.corrected", "P2026-0000001");

            // 배치 크기가 100이어도 같은 계약에서는 한 건만 뽑힌다.
            // 인스턴스를 여러 개 띄워도 뒤 이벤트는 애초에 선택되지 않으므로 순서가 보장된다.
            assertThat(relay.relayOnce()).as("1주기 = 계약당 1건").isEqualTo(1);
            assertThat(relay.relayOnce()).isEqualTo(1);
            assertThat(relay.relayOnce()).isEqualTo(1);
            assertThat(relay.relayOnce()).isZero();

            assertThat(publisher.sentEnvelopes())
                    .as("적재 순서 그대로 나가야 한다. 뒤집히면 claims가 "
                            + "정정을 먼저 처리하고 변경으로 덮어쓴다")
                    .containsExactly("01AAAAAAAAAAAAAAAAAAAAAAAA",
                            "01BBBBBBBBBBBBBBBBBBBBBBBB",
                            "01CCCCCCCCCCCCCCCCCCCCCCCC");
        }

        @Test
        @DisplayName("★ 앞 이벤트가 실패하면 뒤 이벤트는 나가지 않는다")
        void shouldBlockAggregateWhenEarlierEventFails() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            적재("01BBBBBBBBBBBBBBBBBBBBBBBB", "policy.corrected", "P2026-0000001");
            publisher.failNext(1);

            assertThat(relay.relayOnce()).isZero();
            // 두 번째 주기에도 앞 이벤트가 PENDING이므로 뒤 이벤트는 선택되지 않는다
            publisher.failNext(1);
            assertThat(relay.relayOnce()).isZero();

            assertThat(publisher.sent())
                    .as("앞 이벤트를 건너뛰고 뒤 이벤트를 내보내면 순서가 조용히 깨진다")
                    .isEmpty();
            assertThat(statusOf("01BBBBBBBBBBBBBBBBBBBBBBBB")).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("★ 데드레터도 뒤 이벤트를 막는다 — 건너뛰면 순서가 깨진다")
        void shouldKeepBlockingAfterDeadLetter() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            적재("01BBBBBBBBBBBBBBBBBBBBBBBB", "policy.corrected", "P2026-0000001");

            // max-attempts=3 — 세 번 실패하면 FAILED
            publisher.failNext(3);
            relay.relayOnce();
            relay.relayOnce();
            relay.relayOnce();

            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("FAILED");

            // 이제 발행은 성공하는 상태지만, 앞이 FAILED라 뒤는 여전히 막혀 있다
            assertThat(relay.relayOnce()).isZero();
            assertThat(publisher.sent()).isEmpty();
            assertThat(statusOf("01BBBBBBBBBBBBBBBBBBBBBBBB"))
                    .as("막힌 채 알람이 울리는 편이 순서가 깨진 채 흐르는 것보다 낫다")
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("한 계약이 막혀도 다른 계약은 흐른다")
        void shouldNotBlockOtherAggregates() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.unknown_thing", "P2026-0000001");
            적재("01BBBBBBBBBBBBBBBBBBBBBBBB", "policy.issued", "P2026-0000002");

            assertThat(relay.relayOnce())
                    .as("장애가 계약 단위로 격리된다")
                    .isEqualTo(1);
            assertThat(publisher.sentEnvelopes()).containsExactly("01BBBBBBBBBBBBBBBBBBBBBBBB");
        }

        @Test
        @DisplayName("운영자가 FAILED를 PENDING으로 되돌리면 순서대로 다시 흐른다")
        void shouldResumeAfterOperatorReset() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            적재("01BBBBBBBBBBBBBBBBBBBBBBBB", "policy.corrected", "P2026-0000001");

            publisher.failNext(3);
            relay.relayOnce();
            relay.relayOnce();
            relay.relayOnce();
            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("FAILED");

            jdbc.update("UPDATE outbox_event SET status = 'PENDING', attempts = 0 "
                    + "WHERE event_id = ?", "01AAAAAAAAAAAAAAAAAAAAAAAA");

            relay.relayOnce();
            relay.relayOnce();

            assertThat(publisher.sentEnvelopes()).containsExactly(
                    "01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB");
        }
    }

    @Nested
    @DisplayName("재시도와 데드레터")
    class RetryAndDeadLetter {

        @Test
        @DisplayName("실패하면 PENDING을 유지하고 다음 주기에 다시 시도한다")
        void shouldRetryOnFailure() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            publisher.failNext(1);

            assertThat(relay.relayOnce()).isZero();
            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("PENDING");
            assertThat(attemptsOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo(1);

            assertThat(relay.relayOnce()).as("다음 주기에 성공").isEqualTo(1);
            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("재시도 한도를 채우면 FAILED로 두고 자동 재시도를 멈춘다")
        void shouldDeadLetterAfterMaxAttempts() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            publisher.failNext(10);

            relay.relayOnce();
            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("PENDING");
            relay.relayOnce();
            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("PENDING");
            relay.relayOnce();

            assertThat(statusOf("01AAAAAAAAAAAAAAAAAAAAAAAA"))
                    .as("max-attempts=3")
                    .isEqualTo("FAILED");
            assertThat(attemptsOf("01AAAAAAAAAAAAAAAAAAAAAAAA")).isEqualTo(3);

            // 더 이상 집어가지 않는다
            publisher.reset();
            assertThat(relay.relayOnce()).isZero();
            assertThat(publisher.sent()).isEmpty();
        }

        @Test
        @DisplayName("마지막 오류를 기록한다 — 운영자가 원인을 볼 수 있어야 한다")
        void shouldRecordLastError() {
            적재("01AAAAAAAAAAAAAAAAAAAAAAAA", "policy.issued", "P2026-0000001");
            publisher.failNext(1);

            relay.relayOnce();

            String lastError = jdbc.queryForObject(
                    "SELECT last_error FROM outbox_event WHERE event_id = ?",
                    String.class, "01AAAAAAAAAAAAAAAAAAAAAAAA");
            assertThat(lastError).contains("브로커 응답 없음");
        }
    }

    // ─── 기록용 발행기 ────────────────────────────────────────────────────

    @TestConfiguration
    static class RecordingPublisherConfig {
        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    /** 브로커 대신 기록한다. 실패를 시킬 수 있다. */
    static class RecordingEventPublisher implements EventPublisher {

        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private final List<Sent> sent = new CopyOnWriteArrayList<>();
        private int failuresRemaining;

        @Override
        public void publish(String topic, String partitionKey, String envelope) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new EventPublishException("브로커 응답 없음", new RuntimeException("timeout"));
            }
            sent.add(new Sent(topic, partitionKey, envelope));
        }

        void failNext(int times) {
            this.failuresRemaining = times;
        }

        void reset() {
            sent.clear();
            failuresRemaining = 0;
        }

        List<Sent> sent() {
            return List.copyOf(sent);
        }

        /**
         * 발행된 eventId를 순서대로. 순서 검증용.
         *
         * <p>문자열을 잘라 쓰지 않는다. envelope 은 {@code jsonb} 컬럼에서 나오므로
         * PostgreSQL이 정규화한 형태다 — 콜론 뒤에 공백이 붙고 키 순서도 바뀐다.
         * {@code "eventId":"} 로 찾으면 아무것도 못 찾는다. 실제로 그렇게 짰다가 깨졌다.
         */
        List<String> sentEnvelopes() {
            List<String> ids = new ArrayList<>();
            for (Sent s : sent) {
                try {
                    ids.add(MAPPER.readTree(s.envelope()).get("eventId").asText());
                } catch (Exception e) {
                    throw new AssertionError("봉투 JSON을 읽을 수 없습니다: " + s.envelope(), e);
                }
            }
            return ids;
        }

        record Sent(String topic, String partitionKey, String envelope) {
        }
    }
}
