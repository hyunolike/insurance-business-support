package com.insurance.policy.adapter.messaging.outbox;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 이벤트 타입 → 토픽.
 *
 * <p>매핑을 명시적으로 적는다. {@code "policy." + type} 같은 규칙으로 유도하면
 * 오타 하나로 새 토픽이 조용히 생기고, 아무도 구독하지 않는 곳으로 이벤트가 사라진다.
 * 모르는 타입은 발행하지 않고 <b>실패시킨다</b> — Outbox에 남아 알람이 울린다.
 *
 * @see docs/design/04-events-and-integration.md §6
 */
@Component
public class EventTopicRouter {

    /** 계약 이벤트. claims의 읽기모델·재심사 트리거가 여기서 나온다. */
    static final String POLICY_EVENTS = "policy.policy-events.v1";

    private static final Map<String, String> TOPICS = Map.of(
            "policy.issued", POLICY_EVENTS,
            "policy.endorsed", POLICY_EVENTS,
            "policy.corrected", POLICY_EVENTS,
            "policy.lapsed", POLICY_EVENTS,
            "policy.reinstated", POLICY_EVENTS,
            "policy.terminated", POLICY_EVENTS,
            "policy.status_changed", POLICY_EVENTS);

    public String topicFor(String eventType) {
        String topic = TOPICS.get(eventType);
        if (topic == null) {
            throw new IllegalStateException(
                    "토픽이 지정되지 않은 이벤트입니다: %s. EventTopicRouter에 등록하세요. "
                            .formatted(eventType)
                            + "규칙으로 유도하지 않는 것은 의도입니다 — 오타가 조용한 유실이 됩니다.");
        }
        return topic;
    }
}
