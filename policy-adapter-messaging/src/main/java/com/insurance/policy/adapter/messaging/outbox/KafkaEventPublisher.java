package com.insurance.policy.adapter.messaging.outbox;

import com.insurance.policy.application.port.out.EventPublisher;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link EventPublisher}의 Kafka 구현.
 *
 * <p><b>브로커의 확인을 기다린 뒤 반환한다.</b> {@code send()}가 돌려주는 future를
 * 그냥 버리면 전송 실패를 알 수 없고, 릴레이는 유실된 이벤트를 {@code PUBLISHED}로
 * 표시해 버린다. Outbox를 둔 이유가 사라진다.
 *
 * <p>{@code acks=all}과 함께 써야 의미가 있다. 리더만 받고 응답하는 설정이면
 * 여기서 성공을 받아도 리더 장애 시 이벤트가 사라진다.
 *
 * <p>빈 등록은 {@link OutboxRelayConfig}가 한다. 릴레이가 꺼져 있으면 이 빈도 만들어지지
 * 않는다 — 릴레이 외에는 쓰는 곳이 없기 때문이다.
 */
public class KafkaEventPublisher implements EventPublisher {

    /** 브로커 확인 대기 한도. 넘기면 실패로 보고 재시도한다. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, String partitionKey, String envelope) {
        try {
            kafkaTemplate.send(topic, partitionKey, envelope)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            // 인터럽트 상태를 복원하지 않으면 상위의 종료 처리가 멈춘다.
            Thread.currentThread().interrupt();
            throw new EventPublishException(
                    "이벤트 발행이 중단되었습니다: topic=%s key=%s".formatted(topic, partitionKey), e);

        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException(
                    "이벤트 발행에 실패했습니다: topic=%s key=%s".formatted(topic, partitionKey), e);
        }
    }
}
