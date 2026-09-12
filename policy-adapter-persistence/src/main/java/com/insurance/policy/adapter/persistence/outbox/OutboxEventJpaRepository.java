package com.insurance.policy.adapter.persistence.outbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    Optional<OutboxEventEntity> findByEventId(String eventId);

    /**
     * ★ 릴레이가 발행 대상을 집어간다.
     *
     * <p>두 가지를 동시에 보장해야 한다.
     *
     * <h2>1. 중복 발행 방지 — {@code FOR UPDATE SKIP LOCKED}</h2>
     * 릴레이 인스턴스를 여러 개 띄워도 같은 행을 두 번 집지 않는다.
     *
     * <h2>2. ★ 애그리거트 내 순서 보장 — {@code NOT EXISTS}</h2>
     * <b>{@code SKIP LOCKED}만으로는 순서가 깨진다.</b> 인스턴스 A가 1번 이벤트를 잡고
     * 발행하는 동안, 인스턴스 B가 같은 계약의 2번 이벤트를 잡아 먼저 발행할 수 있다.
     * 이 컨텍스트에서 그 결과는 구체적으로 이렇다:
     * <pre>
     *   실제 순서: policy.endorsed(6/1부터 변경) → policy.corrected(과거가 틀렸다)
     *   뒤집히면 : claims가 정정을 먼저 처리해 재심사를 돌리고,
     *              그 다음 도착한 변경 이벤트로 읽기모델을 덮어쓴다
     * </pre>
     * 파티션 키를 {@code policyNo}로 잡아도 소용없다. Kafka는 <b>보낸 순서</b>를 지킬 뿐,
     * 우리가 뒤집어 보내면 뒤집힌 채로 지킨다.
     *
     * <p>그래서 <b>애그리거트당 가장 오래된 미발행 건 하나만</b> 집는다.
     * 앞 이벤트가 아직 {@code PENDING}이면 뒤 이벤트는 애초에 선택되지 않으므로,
     * 인스턴스가 몇 개든 순서가 보장된다. 한 번에 계약당 한 건씩만 나가지만,
     * 계약 이벤트는 드물어서 문제가 되지 않는다.
     *
     * <p>{@code FAILED}도 차단 조건에 넣는다. 영구 실패한 이벤트를 건너뛰고 다음 것을
     * 내보내면 조용히 순서가 깨진다. <b>막힌 채로 알람이 울리는 편이 낫다.</b>
     */
    @Query(value = """
            SELECT o.* FROM outbox_event o
            WHERE o.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_event blocker
                  WHERE blocker.aggregate_id = o.aggregate_id
                    AND blocker.status IN ('PENDING', 'FAILED')
                    AND blocker.id < o.id
              )
            ORDER BY o.id
            LIMIT :batchSize
            FOR UPDATE OF o SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> lockPendingBatch(@Param("batchSize") int batchSize);

    /** 데드레터 감시용. 0이 아니면 해당 계약의 이벤트 흐름이 멈춰 있다는 뜻이다. */
    long countByStatusAndAggregateId(String status, String aggregateId);

    long countByStatus(String status);
}
