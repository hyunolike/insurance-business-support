package com.insurance.policy.adapter.persistence.outbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    Optional<OutboxEventEntity> findByEventId(String eventId);

    /**
     * 릴레이가 발행 대상을 집어간다 (Phase 4).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}가 핵심이다. 릴레이 인스턴스를 여러 개 띄워도
     * 같은 행을 두 번 집지 않으므로 중복 발행이 발생하지 않는다.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING'
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> lockPendingBatch(@Param("batchSize") int batchSize);

    long countByStatus(String status);
}
