package com.insurance.policy.adapter.persistence.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.policy.application.port.out.OutboxAppender;
import com.insurance.policy.domain.shared.DomainEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxAppender}의 JPA 구현.
 *
 * <p>이벤트를 <b>DB에 INSERT만</b> 한다. Kafka로 발행하지 않는다.
 * 호출자의 트랜잭션에 참여하므로, 롤백되면 이벤트도 함께 사라진다.
 *
 * <p>별도의 {@code @Transactional}을 붙이지 않는 것이 의도다.
 * 새 트랜잭션을 열면 상태 변경과 원자성이 깨진다.
 */
@Component
public class OutboxAppenderAdapter implements OutboxAppender {

    private static final String PRODUCER = "business-support";
    private static final int ENVELOPE_VERSION = 1;

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxAppenderAdapter(OutboxEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(List<? extends DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<OutboxEventEntity> entities = events.stream().map(this::toEntity).toList();
        repository.saveAll(entities);
    }

    private OutboxEventEntity toEntity(DomainEvent event) {
        return OutboxEventEntity.pending(
                event.eventId().value(),
                event.eventType(),
                ENVELOPE_VERSION,
                aggregateTypeOf(event),
                event.aggregateId(),
                event.aggregateId(),          // 파티션 키 = 애그리거트 ID (순서 보장 단위)
                serializeEnvelope(event),
                event.occurredAt()
        );
    }

    /**
     * 이벤트 봉투를 만든다.
     *
     * <p>민감정보(성명·계좌번호·KCD 코드)는 이벤트 구현체가 애초에 갖지 않는다.
     * 여기서 걸러내는 것이 아니라 도메인에서 싣지 않는 것이 원칙이다.
     */
    private String serializeEnvelope(DomainEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().value());
        envelope.put("eventType", event.eventType());
        envelope.put("eventVersion", ENVELOPE_VERSION);
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("producer", PRODUCER);
        envelope.put("aggregateType", aggregateTypeOf(event));
        envelope.put("aggregateId", event.aggregateId());
        envelope.put("payload", event);

        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 조용히 넘길 수 없다. 이벤트를 잃으면 하류가 영영 모른다.
            throw new OutboxSerializationException(event.eventType(), event.eventId().value(), e);
        }
    }

    private String aggregateTypeOf(DomainEvent event) {
        // "policy.issued" → "Policy"
        String prefix = event.eventType().split("\\.")[0];
        return Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1);
    }
}
