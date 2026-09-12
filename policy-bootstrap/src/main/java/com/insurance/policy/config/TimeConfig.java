package com.insurance.policy.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 주입.
 *
 * <p>{@code Instant.now()}를 코드 곳곳에서 직접 부르면 시점 재현성을 테스트할 수 없다.
 * 이 시스템에서는 "언제 기록했는가"가 1급 데이터이므로 시계를 주입 가능하게 둔다.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
