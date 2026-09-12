package com.insurance.policy.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * 릴레이가 Outbox를 읽고 상태를 바꾼다.
 *
 * <p>{@link OutboxAppender}(쓰기)와 분리한 이유: 적재는 업무 트랜잭션 안에서 일어나고
 * 이 인터페이스는 릴레이 전용이다. 유스케이스가 실수로 발행 상태를 건드릴 일이 없다.
 *
 * <p>포트로 뺀 이유는 릴레이가 JPA 엔티티를 직접 만지지 않게 하기 위함이다.
 * 그러면 메시징 어댑터가 영속성 어댑터의 내부 타입을 알아야 하고, 두 어댑터가
 * 서로 묶인다. 릴레이가 아는 것은 아래 {@link PendingEvent} 하나면 충분하다.
 */
public interface OutboxStore {

    /**
     * ★ 발행 대상을 잠그고 가져온다.
     *
     * <p>구현은 두 가지를 <b>동시에</b> 보장해야 한다.
     * <ul>
     *   <li><b>중복 발행 방지</b> — 인스턴스를 여러 개 띄워도 같은 행을 두 번 집지 않는다</li>
     *   <li><b>애그리거트 내 순서</b> — 같은 애그리거트에서는 가장 오래된 미발행 건 하나만
     *       돌려준다. 앞 이벤트가 아직 나가지 않았는데 뒤 이벤트를 내보내면
     *       claims가 정정을 먼저 처리하고 변경으로 덮어쓴다</li>
     * </ul>
     *
     * <p>호출자는 트랜잭션 안에 있어야 한다. 잠금이 트랜잭션과 함께 풀리기 때문이다.
     */
    List<PendingEvent> lockPendingBatch(int batchSize);

    /** 발행 성공. */
    void markPublished(long id, Instant at);

    /** 발행 실패. {@code PENDING}을 유지하므로 다음 주기에 다시 시도된다. */
    void markFailed(long id, String error);

    /**
     * 재시도 한도 초과. 자동 재시도를 멈춘다.
     *
     * <p><b>해당 애그리거트의 이후 이벤트도 함께 멈춘다.</b> 건너뛰면 순서가 조용히 깨진다.
     */
    void markDeadLettered(long id, String error);

    /**
     * 발행 대기 중인 이벤트 한 건.
     *
     * @param attempts 지금까지의 실패 횟수. 릴레이가 한도와 비교한다
     * @param envelope 이미 직렬화된 봉투 JSON. 릴레이는 내용을 해석하지 않는다
     */
    record PendingEvent(
            long id,
            String eventId,
            String eventType,
            String aggregateId,
            String partitionKey,
            String envelope,
            int attempts
    ) {
    }
}
