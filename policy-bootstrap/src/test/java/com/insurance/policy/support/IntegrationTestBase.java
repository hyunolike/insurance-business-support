package com.insurance.policy.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 통합 테스트 베이스.
 *
 * <p><b>H2를 쓰지 않는다.</b> v1은 테스트를 H2 + {@code ddl-auto: create-drop} +
 * {@code flyway.enabled: false}로 돌리고 운영은 PostgreSQL + Flyway + {@code validate}로
 * 띄웠다. 결과적으로 {@code V1__init.sql}이 <b>단 한 번도 실행된 적이 없었고</b>,
 * 스키마 불일치는 운영 기동 시점에야 드러날 구조였다.
 *
 * <p>이 저장소의 스키마는 <b>H2에서 생성조차 되지 않는다.</b>
 * Bitemporal 이력의 기간 겹침 방지({@code EXCLUDE USING gist} + {@code daterange}),
 * 이력 불변 트리거, {@code JSONB}, {@code TEXT[]}, 부분 인덱스가 전부 PostgreSQL 고유 기능이다.
 *
 * <p>{@code disabledWithoutDocker = true}는 Docker가 없는 환경에서 테스트를
 * <b>실패가 아니라 건너뜀</b>으로 처리한다. 로컬에서 Docker 없이도 빌드가 돌고,
 * CI(GitHub Actions)에서는 실제로 실행된다.
 *
 * @see docs/design/06-data-model.md §9.1
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("policy")
                    .withUsername("policy")
                    .withPassword("test-only")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // ★ 운영과 동일한 조합으로 검증한다
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // 비밀값은 기본값이 없으므로 테스트에서 주입한다
        registry.add("policy.encryption.key", () -> "test-only-key-not-for-production");

        // Phase 0에서는 Kafka·Redis를 쓰지 않는다
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"));
    }
}
