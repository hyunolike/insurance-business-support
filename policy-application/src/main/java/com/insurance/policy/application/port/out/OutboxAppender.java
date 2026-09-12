package com.insurance.policy.application.port.out;

import com.insurance.policy.domain.shared.DomainEvent;
import java.util.List;

/**
 * 도메인 이벤트를 Outbox에 적재하는 포트.
 *
 * <p>여기서 외부로 발행하지 않는다. 구현체는 DB 테이블에 INSERT만 하며,
 * 실제 Kafka 발행은 별도 릴레이가 담당한다. 그래야 계약 상태 변경과 이벤트 저장이
 * 하나의 트랜잭션이 된다.
 *
 * <p>이 컨텍스트에서 특히 중요한 이유: 소급 정정({@code policy.corrected})이
 * 커밋되었는데 이벤트가 유실되면, claims는 틀린 근거로 계속 지급하게 된다.
 * 반대로 이벤트만 나가고 정정이 롤백되면 claims가 멀쩡한 청구를 재심사 큐에 쌓는다.
 *
 * @see docs/design/04-events-and-integration.md §5
 */
public interface OutboxAppender {

    void append(List<? extends DomainEvent> events);
}
