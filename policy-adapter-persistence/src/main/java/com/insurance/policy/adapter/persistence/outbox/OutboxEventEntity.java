package com.insurance.policy.adapter.persistence.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Outbox 적재용 JPA 엔티티.
 *
 * <p>도메인의 {@code DomainEvent}와 분리되어 있다. 도메인은 영속성을 모르고,
 * 이 엔티티는 도메인 규칙을 모른다. 번역은 {@link OutboxAppenderAdapter}가 한다.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 26)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "partition_key", nullable = false, length = 64)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "envelope", nullable = false)
    private String envelope;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEventEntity() {
        // JPA
    }

    private OutboxEventEntity(String eventId, String eventType, int eventVersion,
                              String aggregateType, String aggregateId, String partitionKey,
                              String envelope, Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.partitionKey = partitionKey;
        this.envelope = envelope;
        this.occurredAt = occurredAt;
        this.status = STATUS_PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    public static OutboxEventEntity pending(String eventId, String eventType, int eventVersion,
                                            String aggregateType, String aggregateId,
                                            String partitionKey, String envelope,
                                            Instant occurredAt) {
        return new OutboxEventEntity(eventId, eventType, eventVersion, aggregateType,
                aggregateId, partitionKey, envelope, occurredAt);
    }

    /** 릴레이가 발행에 성공했을 때 호출한다 (Phase 4). */
    public void markPublished(Instant at) {
        this.status = STATUS_PUBLISHED;
        this.publishedAt = at;
        this.lastError = null;
    }

    /** 발행 실패. 재시도 횟수를 누적하고 마지막 오류를 남긴다 (Phase 4). */
    public void markFailed(String error) {
        this.attempts += 1;
        this.lastError = error;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getEnvelope() {
        return envelope;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
