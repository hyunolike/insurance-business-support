package com.insurance.policy.adapter.persistence.outbox;

/**
 * 이벤트 봉투 직렬화 실패.
 *
 * <p>이 예외는 트랜잭션을 롤백시킨다. 의도된 동작이다 —
 * 이벤트를 잃은 채 상태만 바뀌면 하류 시스템이 영영 알지 못한다.
 * 차라리 전체를 되돌리고 실패로 드러나는 편이 안전하다.
 */
public class OutboxSerializationException extends RuntimeException {

    public OutboxSerializationException(String eventType, String eventId, Throwable cause) {
        super("이벤트 봉투 직렬화에 실패했습니다: eventType=%s, eventId=%s"
                .formatted(eventType, eventId), cause);
    }
}
