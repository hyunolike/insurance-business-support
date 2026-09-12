package com.insurance.policy.adapter.messaging.outbox;

import com.insurance.policy.application.port.out.EventPublisher;
import com.insurance.policy.application.port.out.OutboxStore;
import com.insurance.policy.application.port.out.OutboxStore.PendingEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ Outbox 릴레이 — DB에 쌓인 이벤트를 브로커로 내보낸다.
 *
 * <p>유스케이스는 {@code OutboxAppender}로 DB에만 적재한다. 실제 발행은 여기서,
 * <b>커밋된 뒤에</b> 일어난다. 이 분리가 Transactional Outbox의 전부다.
 *
 * <h2>전달 보장: at-least-once</h2>
 * 발행에 성공하고 {@code PUBLISHED} 표시를 커밋하기 직전에 죽으면 같은 이벤트가 다시 나간다.
 * <b>정확히 한 번은 보장하지 않으며, 보장하려 하지도 않는다</b> — 분산 트랜잭션 없이는
 * 불가능하고, 그 대가가 유실 위험보다 크다. 대신 소비자가 멱등해야 한다.
 * {@code processed_event} 테이블이 그 용도이고, {@code eventId}(ULID)가 중복 제거 키다.
 *
 * <h2>순서</h2>
 * 뒤집히지 않는 것은 {@link OutboxStore#lockPendingBatch}가 책임진다 —
 * 애그리거트당 가장 오래된 미발행 건 하나만 돌려준다.
 * 여기서 지킬 것은 하나다: <b>한 이벤트가 실패하면 그 애그리거트를 멈춘다.</b>
 * 실패한 건을 건너뛰고 다음 것을 내보내는 순간 순서가 조용히 깨진다.
 *
 * @see docs/design/04-events-and-integration.md §4
 */
@Component
@ConditionalOnProperty(name = "policy.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore outboxStore;
    private final EventPublisher eventPublisher;
    private final EventTopicRouter topicRouter;
    private final OutboxRelayProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OutboxRelay(OutboxStore outboxStore,
                       EventPublisher eventPublisher,
                       EventTopicRouter topicRouter,
                       OutboxRelayProperties properties,
                       PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.outboxStore = outboxStore;
        this.eventPublisher = eventPublisher;
        this.topicRouter = topicRouter;
        this.properties = properties;
        this.clock = clock;

        // @Transactional 대신 TransactionTemplate을 쓴다.
        //
        // poll()이 같은 빈의 relayOnce()를 부르면 프록시를 거치지 않아
        // @Transactional이 조용히 무시된다(자기 호출 함정). 그러면 FOR UPDATE가
        // 트랜잭션 없이 실행되고, 잠금이 즉시 풀려 여러 인스턴스가 같은 행을 집는다.
        // 컴파일도 되고 단일 인스턴스 테스트도 통과하지만, 운영에서 중복 발행으로 나타난다.
        // 경계를 눈에 보이게 두어 그 함정을 아예 없앤다.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 폴링 진입점.
     *
     * <p>예외를 밖으로 내보내지 않는다. {@code @Scheduled}는 예외가 나와도 다음 주기에
     * 다시 돌지만, 로그 없이 조용히 실패하면 이벤트가 쌓이는 것을 아무도 모른다.
     */
    @Scheduled(
            fixedDelayString = "${policy.outbox.relay.fixed-delay:1000}",
            initialDelayString = "${policy.outbox.relay.initial-delay:5000}")
    public void poll() {
        try {
            int published = relayOnce();
            if (published > 0) {
                log.debug("Outbox 발행 {}건", published);
            }
        } catch (Exception e) {
            // 다음 주기에 다시 시도한다. 여기서 멈추면 릴레이가 영영 죽는다.
            log.error("Outbox 릴레이 주기 실패", e);
        }
    }

    /**
     * 한 배치를 발행한다. 테스트는 스케줄러를 기다리지 않고 이 메서드를 직접 부른다.
     *
     * <p>매 배치를 독립 트랜잭션에 둔다. 잠금을 오래 쥐면 업무 트랜잭션이
     * Outbox INSERT에서 대기한다.
     *
     * <p><b>발행이 트랜잭션 안에서 일어난다.</b> 브로커가 받았는데 커밋 전에 죽으면
     * 재발행되므로 중복이 생길 수 있다 — 위에 적은 at-least-once가 이것이다.
     * 반대로 하면(커밋 먼저, 발행 나중) 유실이 생기고, 유실이 훨씬 나쁘다.
     *
     * @return 이번 배치에서 발행에 성공한 건수
     */
    public int relayOnce() {
        Integer published = transactionTemplate.execute(status -> {
            List<PendingEvent> batch = outboxStore.lockPendingBatch(properties.batchSize());

            int count = 0;
            for (PendingEvent event : batch) {
                if (publish(event)) {
                    count++;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** @return 발행 성공 여부 */
    private boolean publish(PendingEvent event) {
        try {
            eventPublisher.publish(
                    topicRouter.topicFor(event.eventType()),
                    event.partitionKey(),
                    event.envelope());

            outboxStore.markPublished(event.id(), Instant.now(clock));
            return true;

        } catch (Exception e) {
            recordFailure(event, e);
            return false;
        }
    }

    private void recordFailure(PendingEvent event, Exception cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        int attemptsAfter = event.attempts() + 1;

        if (attemptsAfter >= properties.maxAttempts()) {
            outboxStore.markDeadLettered(event.id(), message);
            // 이 애그리거트의 이후 이벤트가 전부 멈춘다. 운영자가 봐야 한다.
            log.error("Outbox 이벤트 발행 영구 실패 — aggregateId={} eventType={} eventId={} "
                            + "attempts={}. 이 계약의 이후 이벤트가 멈춥니다.",
                    event.aggregateId(), event.eventType(), event.eventId(),
                    attemptsAfter, cause);
        } else {
            outboxStore.markFailed(event.id(), message);
            log.warn("Outbox 이벤트 발행 실패, 재시도 예정 — eventId={} attempts={}/{}",
                    event.eventId(), attemptsAfter, properties.maxAttempts(), cause);
        }
    }
}
