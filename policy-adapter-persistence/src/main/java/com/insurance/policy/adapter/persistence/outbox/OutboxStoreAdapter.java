package com.insurance.policy.adapter.persistence.outbox;

import com.insurance.policy.application.port.out.OutboxStore;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxStore}의 JPA 구현.
 *
 * <p>{@code mark*} 메서드가 {@code findById}로 다시 읽는 것이 낭비처럼 보이지만,
 * 같은 트랜잭션 안에서 {@link #lockPendingBatch}가 이미 올려둔 영속성 컨텍스트의
 * 1차 캐시에서 나오므로 추가 SELECT가 발생하지 않는다. 변경은 더티 체킹으로 flush된다.
 *
 * <p>엔티티를 포트 밖으로 내보내지 않는다. 내보내면 릴레이가 트랜잭션 밖에서
 * 지연 로딩을 건드리거나 엔티티를 직접 수정할 수 있게 된다.
 */
@Component
public class OutboxStoreAdapter implements OutboxStore {

    private final OutboxEventJpaRepository repository;

    public OutboxStoreAdapter(OutboxEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<PendingEvent> lockPendingBatch(int batchSize) {
        return repository.lockPendingBatch(batchSize).stream()
                .map(e -> new PendingEvent(
                        e.getId(),
                        e.getEventId(),
                        e.getEventType(),
                        e.getAggregateId(),
                        e.getPartitionKey(),
                        e.getEnvelope(),
                        e.getAttempts()))
                .toList();
    }

    @Override
    public void markPublished(long id, Instant at) {
        find(id).markPublished(at);
    }

    @Override
    public void markFailed(long id, String error) {
        find(id).markFailed(error);
    }

    @Override
    public void markDeadLettered(long id, String error) {
        find(id).markDeadLettered(error);
    }

    private OutboxEventEntity find(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalStateException(
                "잠근 Outbox 행이 사라졌습니다: id=%d. 같은 트랜잭션 안에서는 일어날 수 없다."
                        .formatted(id)));
    }
}
