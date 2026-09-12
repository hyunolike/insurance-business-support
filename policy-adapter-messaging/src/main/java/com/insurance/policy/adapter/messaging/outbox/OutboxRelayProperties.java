package com.insurance.policy.adapter.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 릴레이 설정.
 *
 * @param batchSize   한 주기에 집어갈 최대 건수. 애그리거트당 1건만 뽑히므로
 *                    실제로는 "동시에 처리할 서로 다른 계약 수"에 가깝다
 * @param maxAttempts 이 횟수를 채우면 {@code FAILED}로 두고 자동 재시도를 멈춘다.
 *                    해당 계약의 이후 이벤트도 함께 멈추므로 알람 대상이다
 */
@ConfigurationProperties(prefix = "policy.outbox.relay")
public record OutboxRelayProperties(Integer batchSize, Integer maxAttempts) {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    public OutboxRelayProperties {
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;

        if (batchSize < 1) {
            throw new IllegalArgumentException("배치 크기는 1 이상이어야 합니다: " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("재시도 한도는 1 이상이어야 합니다: " + maxAttempts);
        }
    }
}
