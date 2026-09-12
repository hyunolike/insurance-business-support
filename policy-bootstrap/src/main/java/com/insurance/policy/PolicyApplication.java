package com.insurance.policy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * business-support 애플리케이션.
 *
 * <p>{@code @EnableScheduling}은 Outbox 릴레이의 폴링을 위한 것이다. 이것이 없으면
 * {@code @Scheduled}가 조용히 무시되고, 이벤트는 DB에 쌓이기만 한 채 아무도 모른다.
 * 릴레이 자체는 {@code policy.outbox.relay.enabled}로 켜고 끈다.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PolicyApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyApplication.class, args);
    }
}
