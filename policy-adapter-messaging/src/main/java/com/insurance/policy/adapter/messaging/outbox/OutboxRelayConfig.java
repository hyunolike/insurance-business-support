package com.insurance.policy.adapter.messaging.outbox;

import com.insurance.policy.application.port.out.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 릴레이 관련 빈 등록.
 *
 * <p><b>바깥 조건이 "릴레이가 켜져 있는가"인 것이 핵심이다.</b>
 *
 * <p>처음에는 {@link KafkaEventPublisher}에 {@code @Component}와
 * {@code publisher=kafka} 조건만 달아두었다. 그러면 릴레이를 꺼도 발행기 빈은 만들어지고,
 * 그 빈이 {@code KafkaTemplate}을 요구한다. Kafka 자동설정을 제외한 통합 테스트에서는
 * <b>컨텍스트 로딩 자체가 실패했다</b> — 릴레이를 껐는데도.
 *
 * <p>발행기는 릴레이 외에 쓰는 곳이 없다. 그러니 릴레이가 꺼져 있으면 함께 없어야 한다.
 */
@Configuration
@ConditionalOnProperty(name = "policy.outbox.relay.enabled", havingValue = "true")
public class OutboxRelayConfig {

    /**
     * Kafka 발행기.
     *
     * <p>{@code publisher} 값으로 구현을 고른다. 테스트는 이 값을 {@code kafka}가 아닌
     * 것으로 두고 자기 구현을 등록한다 — 브로커 없이 릴레이 로직을 검증하기 위함이다.
     */
    @Bean
    @ConditionalOnProperty(name = "policy.outbox.relay.publisher", havingValue = "kafka")
    EventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        return new KafkaEventPublisher(kafkaTemplate);
    }
}
