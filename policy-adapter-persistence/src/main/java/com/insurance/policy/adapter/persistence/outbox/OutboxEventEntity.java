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

    /** 릴레이가 발행에 성공했을 때 호출한다. */
    public void markPublished(Instant at) {
        this.status = STATUS_PUBLISHED;
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * 발행 실패. 재시도 횟수를 누적하고 마지막 오류를 남긴다.
     *
     * <p>{@code PENDING}을 유지하므로 다음 폴링에서 다시 시도된다.
     */
    public void markFailed(String error) {
        this.attempts += 1;
        this.lastError = truncate(error);
    }

    /**
     * 재시도 한도를 넘겼다. 더 이상 자동으로 시도하지 않는다.
     *
     * <p><b>이 애그리거트의 이후 이벤트도 함께 멈춘다.</b> 릴레이 쿼리가 {@code FAILED}를
     * 차단 조건에 넣기 때문이다. 건너뛰고 다음 것을 내보내면 순서가 조용히 깨지는데,
     * 계약 이벤트에서 그것은 claims의 잘못된 재심사나 잘못된 지급으로 이어진다.
     * 막힌 채로 알람이 울리는 편이 낫다.
     *
     * <p>운영자가 원인을 고친 뒤 {@code PENDING}으로 되돌리면 순서대로 다시 흐른다.
     */
    public void markDeadLettered(String error) {
        this.attempts += 1;
        this.lastError = truncate(error);
        this.status = STATUS_FAILED;
    }

    /** 오류 메시지가 무한정 길어지지 않게 자른다. 스택트레이스는 로그에 남는다. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000) + "…(생략)";
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
