package com.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurance.policy.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 마이그레이션이 실제 PostgreSQL에서 실행되는지 검증한다.
 *
 * <p>이 저장소는 PostgreSQL 고유 기능(btree_gist, EXCLUDE 제약, daterange)에
 * 강하게 의존한다. 마이그레이션이 실제 PostgreSQL에서 도는지 확인하지 않으면
 * 그 의존이 운영 기동 시점에야 드러난다.
 */
@DisplayName("Flyway 마이그레이션")
class FlywayMigrationTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V1·V2가 적용되고 기반 테이블이 생성된다")
    void shouldApplyBaselineMigration() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(applied).contains("1", "2");

        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);
        assertThat(tables)
                .contains("outbox_event", "processed_event", "idempotency_record");
    }

    @Test
    @DisplayName("btree_gist 확장이 설치된다 — Bitemporal 기간 겹침 방지 제약의 전제")
    void shouldInstallBtreeGist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer installed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class);

        assertThat(installed)
                .as("EXCLUDE USING gist (policy_no WITH =, daterange(...) WITH &&) 에 필요하다. "
                        + "이 확장이 없으면 Phase 1의 이력 테이블을 만들 수 없다.")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("daterange 겹침 배타 제약을 실제로 만들 수 있다 — Phase 1 이력 테이블의 전제")
    void shouldSupportExcludeConstraintOnDateRange() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("""
                CREATE TEMP TABLE temporal_probe (
                    policy_no  VARCHAR(24) NOT NULL,
                    valid_from DATE NOT NULL,
                    valid_to   DATE NOT NULL,
                    superseded_at TIMESTAMPTZ NULL,
                    EXCLUDE USING gist (
                        policy_no WITH =,
                        daterange(valid_from, valid_to) WITH &&
                    ) WHERE (superseded_at IS NULL)
                )
                """);

        jdbc.update("INSERT INTO temporal_probe VALUES ('P-1', '2026-01-01', '2026-06-01', NULL)");

        List<String> violations = new ArrayList<>();
        try {
            // 같은 계약, 겹치는 기간 → DB가 거부해야 한다
            jdbc.update("INSERT INTO temporal_probe VALUES ('P-1', '2026-03-01', '2026-09-01', NULL)");
            violations.add("겹치는 유효기간이 INSERT되었습니다");
        } catch (Exception expected) {
            // 정상
        }

        // 겹치지 않으면 통과해야 한다
        jdbc.update("INSERT INTO temporal_probe VALUES ('P-1', '2026-06-01', '2026-12-01', NULL)");

        assertThat(violations)
                .as("애플리케이션 로직이 틀려도 모순된 이력이 저장되지 않아야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("Outbox 부분 인덱스가 만들어진다 — PENDING만 인덱싱해 릴레이 폴링 비용을 낮춘다")
    void shouldCreatePartialIndex() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String definition = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'outbox_event' AND indexname = 'idx_outbox_pending'
                """, String.class);

        assertThat(definition)
                .as("부분 인덱스는 PostgreSQL 고유 기능이다. H2였다면 이 검증이 불가능했다.")
                .contains("WHERE")
                .contains("PENDING");
    }

    @Test
    @DisplayName("status 제약이 잘못된 값을 거부한다")
    void shouldEnforceStatusCheckConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> violations = new ArrayList<>();

        try {
            jdbc.update("""
                    INSERT INTO outbox_event
                        (event_id, event_type, aggregate_type, aggregate_id,
                         partition_key, envelope, occurred_at, status)
                    VALUES ('01JBX7K3QM8W2ZP4NRTV9C6DY0', 'test.event', 'Test', 'T-1',
                            'T-1', '{}'::jsonb, NOW(), 'NOT_A_VALID_STATUS')
                    """);
            violations.add("잘못된 status가 INSERT되었습니다");
        } catch (Exception expected) {
            // CHECK 제약이 거부하는 것이 정상이다
        }

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("event_id 유니크 제약이 중복 적재를 막는다")
    void shouldRejectDuplicateEventId() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String eventId = "01JBX7K3QM8W2ZP4NRTV9C6DY1";

        jdbc.update("""
                INSERT INTO outbox_event
                    (event_id, event_type, aggregate_type, aggregate_id,
                     partition_key, envelope, occurred_at)
                VALUES (?, 'test.event', 'Test', 'T-1', 'T-1', '{}'::jsonb, NOW())
                """, eventId);

        List<String> violations = new ArrayList<>();
        try {
            jdbc.update("""
                    INSERT INTO outbox_event
                        (event_id, event_type, aggregate_type, aggregate_id,
                         partition_key, envelope, occurred_at)
                    VALUES (?, 'test.event', 'Test', 'T-1', 'T-1', '{}'::jsonb, NOW())
                    """, eventId);
            violations.add("중복 event_id가 INSERT되었습니다");
        } catch (Exception expected) {
            // UNIQUE 제약이 거부하는 것이 정상이다
        }

        assertThat(violations).isEmpty();
    }
}
