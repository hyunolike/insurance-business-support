package com.insurance.policy.application.port.out;

/**
 * 이벤트를 외부(메시지 브로커)로 내보낸다.
 *
 * <p><b>{@code @Transactional} 안에서 이 포트를 호출하는 곳은 Outbox 릴레이 하나뿐이다.</b>
 * 유스케이스는 {@link OutboxAppender}로 DB에만 적재한다 — 커밋 전에 발행하면
 * 롤백된 트랜잭션의 이벤트가 외부로 나간다.
 *
 * <p>포트로 분리한 이유는 두 가지다.
 * <ul>
 *   <li>릴레이의 <b>순서·재시도·데드레터 판단</b>을 Kafka 없이 검증할 수 있다.
 *       통합 테스트는 PostgreSQL만 띄우고 이 포트를 기록용 구현으로 바꿔 끼운다.</li>
 *   <li>브로커를 바꿔도 릴레이 로직이 그대로다.</li>
 * </ul>
 */
public interface EventPublisher {

    /**
     * 발행하고 <b>브로커가 받았음을 확인한 뒤</b> 반환한다.
     *
     * <p>비동기로 던져놓고 반환하면 안 된다. 릴레이는 이 메서드가 정상 반환한 것을
     * 근거로 {@code PUBLISHED}로 표시하는데, 실제로는 유실됐다면 이벤트가 영영 사라진다.
     * Outbox의 존재 이유가 바로 그 유실을 막는 것이다.
     *
     * @param topic        대상 토픽
     * @param partitionKey 순서 보장 단위. 같은 키는 같은 파티션으로 간다
     * @param envelope     이미 직렬화된 이벤트 봉투 JSON
     * @throws EventPublishException 발행에 실패했거나 확인을 받지 못했을 때
     */
    void publish(String topic, String partitionKey, String envelope);

    /** 발행 실패. 릴레이가 잡아서 재시도 횟수를 누적한다. */
    class EventPublishException extends RuntimeException {
        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
