package com.insurance.policy;

import static com.insurance.policy.domain.policy.PolicyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.policy.application.policy.PolicyCommandService;
import com.insurance.policy.application.port.out.PolicyRepository;
import com.insurance.policy.domain.policy.CoverageTerms;
import com.insurance.policy.domain.policy.Exclusion;
import com.insurance.policy.domain.policy.ExclusionId;
import com.insurance.policy.domain.policy.ExclusionType;
import com.insurance.policy.domain.policy.KcdRange;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.shared.vo.Money;
import com.insurance.policy.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bitemporal 이력이 실제 PostgreSQL에서 동작하는지 검증한다.
 *
 * <p>도메인 단위 테스트({@code PolicySnapshotTest})가 시점 판정 로직을 보장한다면,
 * 여기서는 그것이 <b>DB 제약과 함께</b> 성립하는지를 본다:
 * EXCLUDE 겹침 방지, 이력 불변 트리거, 시점 조회 SQL.
 */
@DisplayName("계약 Bitemporal 통합")
class PolicyBitemporalIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PolicyCommandService commandService;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        // DELETE 로는 초기화할 수 없다. 이력 테이블과 correction_log 는 append-only 라
        // 삭제가 막혀 있고(트리거가 예외를 던진다), FK 때문에 policy 도 지워지지 않는다.
        // TRUNCATE 는 행 트리거를 타지 않으므로 픽스처 초기화에 쓴다 —
        // 애플리케이션 코드가 이 경로를 쓰는 일은 없어야 한다.
        jdbc.execute("""
                TRUNCATE correction_log, exclusion_version, coverage_version,
                         policy_version, policy, outbox_event
                RESTART IDENTITY CASCADE
                """);
    }

    private void 부담보계약저장() {
        commandService.issue(new PolicyCommandService.IssuePolicyCommand(
                POLICY_NO, product(), "HOLDER-1", insured(), period(),
                계약일, 계약일,
                new ArrayList<>(List.of(급여입원담보(), 급여통원담보(), 비급여통원담보())),
                new ArrayList<>(List.of(척추부담보조건())),
                "A2026-0005678", "U-2026-0012"));
    }

    @Nested
    @DisplayName("시점 조회")
    class PointInTime {

        @Test
        @DisplayName("사고일 시점의 계약을 재현한다")
        void shouldLoadSnapshotAtAccidentDate() {
            부담보계약저장();

            var policy = policyRepository.findAsOf(POLICY_NO, 사고일, Instant.now()).orElseThrow();
            var snapshot = policy.snapshotAsOf(사고일, Instant.now());

            assertThat(snapshot.policyStatusAsOf()).isEqualTo(PolicyStatus.IN_FORCE);
            assertThat(snapshot.coverages()).hasSize(3);
            assertThat(snapshot.exclusions()).singleElement().satisfies(e -> {
                assertThat(e.target()).isEqualTo("척추 및 그 부속기관");
                assertThat(e.kcdRanges()).containsExactly(KcdRange.parse("M40-M54"));
                assertThat(e.uwCaseNo()).isEqualTo("U-2026-0012");
            });
        }

        @Test
        @DisplayName("담보 조건이 왕복 후에도 정확하다 — 종별 최소공제금액 포함")
        void shouldRoundTripCoverageTerms() {
            부담보계약저장();

            var snapshot = policyRepository.findAsOf(POLICY_NO, 사고일, Instant.now())
                    .orElseThrow().snapshotAsOf(사고일, Instant.now());

            var 급여통원 = snapshot.coverages().stream()
                    .filter(c -> c.coverageCode().equals(PolicyFixturesRef.급여통원코드()))
                    .findFirst().orElseThrow();

            assertThat(급여통원.terms().coinsuranceRate())
                    .isEqualByComparingTo(new BigDecimal("0.20"));
            assertThat(급여통원.terms().minDeductibleFor(
                    com.insurance.policy.domain.policy.InstitutionGrade.TERTIARY))
                    .as("상급종합병원 최소공제 2만원 — claims의 자기부담금 계산에 그대로 쓰인다")
                    .isEqualTo(20_000L);
            assertThat(급여통원.terms().perVisitLimit()).isEqualTo(200_000L);
            assertThat(급여통원.terms().annualCountLimit()).isEqualTo(100);

            var 급여입원 = snapshot.coverages().stream()
                    .filter(c -> c.coverageCode().equals(PolicyFixturesRef.급여입원코드()))
                    .findFirst().orElseThrow();
            assertThat(급여입원.terms().minDeductibleFor(
                    com.insurance.policy.domain.policy.InstitutionGrade.TERTIARY))
                    .as("입원은 최소공제금액이 없다")
                    .isNull();
            assertThat(급여입원.terms().annualLimit()).isEqualTo(50_000_000L);
        }

        @Test
        @DisplayName("★ 정정 후에도 정정 전 판단이 재현된다 (DB 왕복)")
        void shouldReproducePastAfterCorrection() {
            부담보계약저장();

            var 정정전 = policyRepository.findAsOf(POLICY_NO, 사고일, Instant.now())
                    .orElseThrow().snapshotAsOf(사고일, Instant.now());
            assertThat(정정전.exclusions()).hasSize(1);
            Instant 정정직전 = Instant.now();

            commandService.correctExclusion(new PolicyCommandService.CorrectExclusionCommand(
                    POLICY_NO, 척추부담보, null,
                    "부담보 조건 착오 입력 정정", "UW-0007", "UW-MANAGER-003"));

            // 지금 아는 진실 — 부담보 없음
            var 정정후 = policyRepository.findAsOf(POLICY_NO, 사고일, Instant.now())
                    .orElseThrow().snapshotAsOf(사고일, Instant.now());
            assertThat(정정후.exclusions()).isEmpty();

            // 정정 직전 시점 — 부담보 있음. claims의 4월 부지급 근거가 살아있다
            var 과거시점 = policyRepository.findAsOf(POLICY_NO, 사고일, 정정직전)
                    .orElseThrow().snapshotAsOf(사고일, 정정직전);
            assertThat(과거시점.exclusions())
                    .as("정정 전 판단을 설명할 수 없으면 이 시스템의 존재 이유가 사라진다")
                    .hasSize(1);
        }

        @Test
        @DisplayName("정정은 correction_log에 승인자와 함께 기록된다")
        void shouldRecordCorrectionLog() {
            부담보계약저장();

            commandService.correctExclusion(new PolicyCommandService.CorrectExclusionCommand(
                    POLICY_NO, 척추부담보, null, "착오 정정", "UW-0007", "UW-MANAGER-003"));

            var row = jdbc.queryForMap("""
                    SELECT correction_type, requested_by, approved_by,
                           previous_version, new_version, reason
                    FROM correction_log WHERE policy_no = ?
                    """, POLICY_NO.value());

            assertThat(row.get("correction_type")).isEqualTo("REMOVE_EXCLUSION");
            assertThat(row.get("requested_by")).isEqualTo("UW-0007");
            assertThat(row.get("approved_by")).isEqualTo("UW-MANAGER-003");
            assertThat(row.get("previous_version")).isEqualTo(1);
            assertThat(row.get("new_version")).isEqualTo(2);
        }

        @Test
        @DisplayName("정정으로 부담보를 교체할 수 있다")
        void shouldReplaceExclusion() {
            부담보계약저장();

            Exclusion 좁힌범위 = Exclusion.create(
                    ExclusionId.of("EXC-2026-000046"), ExclusionType.BODY_PART, "요추부",
                    List.of(KcdRange.parse("M50-M54")), "부담보 범위 정정", "U-2026-0012",
                    계약일, 만기일, Instant.now());

            commandService.correctExclusion(new PolicyCommandService.CorrectExclusionCommand(
                    POLICY_NO, 척추부담보, 좁힌범위,
                    "부담보 범위가 과도하게 넓게 입력됨", "UW-0007", "UW-MANAGER-003"));

            var snapshot = policyRepository.findAsOf(POLICY_NO, 사고일, Instant.now())
                    .orElseThrow().snapshotAsOf(사고일, Instant.now());

            assertThat(snapshot.exclusionsMatching("M51.2")).hasSize(1);
            assertThat(snapshot.exclusionsMatching("M43.0"))
                    .as("경추는 이제 저촉되지 않는다")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("변경(Endorsement)")
    class Endorsement {

        @Test
        @DisplayName("변경 후에도 과거 스냅샷은 그대로다")
        void shouldNotAffectPast() {
            부담보계약저장();
            LocalDate 변경일 = LocalDate.of(2026, 9, 1);

            commandService.endorse(new PolicyCommandService.EndorseCoverageCommand(
                    POLICY_NO, PolicyFixturesRef.비급여통원코드(),
                    CoverageTerms.flatDeductible(new BigDecimal("0.30"), 30_000L, 300_000L, 100),
                    Money.ofWon(300_000L), 변경일, "고객 요청", "CS-001"));

            var 과거 = policyRepository.findAsOf(POLICY_NO, 사고일, Instant.now())
                    .orElseThrow().snapshotAsOf(사고일, Instant.now())
                    .coverages().stream()
                    .filter(c -> c.coverageCode().equals(PolicyFixturesRef.비급여통원코드()))
                    .findFirst().orElseThrow();
            assertThat(과거.terms().perVisitLimit()).isEqualTo(200_000L);

            var 현재 = policyRepository.findAsOf(POLICY_NO, LocalDate.of(2026, 10, 1), Instant.now())
                    .orElseThrow().snapshotAsOf(LocalDate.of(2026, 10, 1), Instant.now())
                    .coverages().stream()
                    .filter(c -> c.coverageCode().equals(PolicyFixturesRef.비급여통원코드()))
                    .findFirst().orElseThrow();
            assertThat(현재.terms().perVisitLimit()).isEqualTo(300_000L);
        }
    }

    @Nested
    @DisplayName("DB 제약 — 애플리케이션 버그가 있어도 막는다")
    class DatabaseConstraints {

        @Test
        @DisplayName("★ 겹치는 유효기간은 EXCLUDE 제약이 거부한다")
        void shouldRejectOverlappingPeriods() {
            부담보계약저장();

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO coverage_version (policy_no, coverage_code, coverage_name,
                            benefit_category, treatment_types, insured_amount, coinsurance_rate,
                            annual_limit, valid_from, valid_to, recorded_at, change_type)
                    VALUES (?, 'COV-INPT-COVERED', '중복 담보', 'COVERED', '{INPATIENT}',
                            1, 0.2, 1, '2026-06-01', '2027-06-01', NOW(), 'CREATE')
                    """, POLICY_NO.value()))
                    .as("이미 2026-01-01~2031-01-01 구간이 있으므로 겹친다")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("★ 이력 레코드 UPDATE를 트리거가 거부한다")
        void shouldRejectHistoryMutation() {
            부담보계약저장();

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE exclusion_version SET target = '변조된 대상'
                    WHERE policy_no = ?
                    """, POLICY_NO.value()))
                    .as("과거를 수정하면 claims의 판단을 재현할 수 없게 된다")
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("대체 마킹 외에는 수정할 수 없습니다");
        }

        @Test
        @DisplayName("superseded_at 마킹은 허용된다 — 유일하게 허용되는 UPDATE")
        void shouldAllowSupersedeMarking() {
            부담보계약저장();

            int updated = jdbc.update("""
                    UPDATE exclusion_version SET superseded_at = NOW()
                    WHERE policy_no = ? AND superseded_at IS NULL
                    """, POLICY_NO.value());

            assertThat(updated).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 정정된 기록의 superseded_at은 다시 바꿀 수 없다")
        void shouldRejectRewritingSupersededAt() {
            부담보계약저장();
            jdbc.update("UPDATE exclusion_version SET superseded_at = NOW() WHERE policy_no = ?",
                    POLICY_NO.value());

            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE exclusion_version SET superseded_at = NOW() + interval '1 day'
                    WHERE policy_no = ?
                    """, POLICY_NO.value()))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("다시 바꿀 수 없습니다");
        }

        @Test
        @DisplayName("KCD 범위 없는 부담보는 거부한다 — claims가 판정할 수 없다")
        void shouldRejectExclusionWithoutKcd() {
            부담보계약저장();

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO exclusion_version (policy_no, exclusion_id, type, target,
                            kcd_ranges, reason, valid_from, valid_to, recorded_at, change_type)
                    VALUES (?, 'EXC-2026-000099', 'BODY_PART', '무범위',
                            '{}', '사유', '2026-01-01', '2031-01-01', NOW(), 'CREATE')
                    """, POLICY_NO.value()))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("정정 요청자와 승인자가 같으면 DB가 거부한다")
        void shouldRejectSelfApprovedCorrection() {
            부담보계약저장();

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO correction_log (policy_no, correction_type, affected_elements,
                            scope_valid_from, scope_valid_to, previous_version, new_version,
                            reason, requested_by, approved_by)
                    VALUES (?, 'REMOVE_EXCLUSION', '{EXCLUSION}', '2026-01-01', '2031-01-01',
                            1, 2, '자가 승인', 'UW-0007', 'UW-0007')
                    """, POLICY_NO.value()))
                    .as("과거를 바꾸는 행위는 단독으로 할 수 없다")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("정정 이력은 수정·삭제할 수 없다 — 조용히 무시가 아니라 예외")
        void shouldMakeCorrectionLogImmutable() {
            부담보계약저장();
            commandService.correctExclusion(new PolicyCommandService.CorrectExclusionCommand(
                    POLICY_NO, 척추부담보, null, "착오", "UW-0007", "UW-MGR"));

            // 조용히 무시(RULE ... DO INSTEAD NOTHING)면 정리 스크립트를 돌린 운영자도,
            // 버그가 있는 배치도 삭제에 성공했다고 믿는다. 감사 기록에서는 시끄러운 실패가 옳다.
            assertThatThrownBy(() ->
                    jdbc.update("UPDATE correction_log SET reason = '변조' WHERE policy_no = ?",
                            POLICY_NO.value()))
                    .as("수정 시도는 예외로 거부된다")
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("정정 이력은 수정·삭제할 수 없습니다");

            assertThatThrownBy(() ->
                    jdbc.update("DELETE FROM correction_log WHERE policy_no = ?",
                            POLICY_NO.value()))
                    .as("삭제 시도도 예외로 거부된다")
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("정정 이력은 수정·삭제할 수 없습니다");

            var row = jdbc.queryForMap(
                    "SELECT reason FROM correction_log WHERE policy_no = ?", POLICY_NO.value());
            assertThat(row.get("reason")).isEqualTo("착오");
        }
    }

    @Nested
    @DisplayName("이벤트")
    class Events {

        @Test
        @DisplayName("계약 성립은 policy.issued를 Outbox에 남긴다")
        void shouldAppendPolicyIssued() {
            부담보계약저장();

            var row = jdbc.queryForMap("""
                    SELECT event_type, aggregate_type, aggregate_id, partition_key, status,
                           envelope::text AS envelope
                    FROM outbox_event WHERE aggregate_id = ?
                    """, POLICY_NO.value());

            assertThat(row.get("event_type")).isEqualTo("policy.issued");
            assertThat(row.get("aggregate_type")).isEqualTo("Policy");
            assertThat(row.get("partition_key")).isEqualTo(POLICY_NO.value());
            assertThat(row.get("status")).isEqualTo("PENDING");
            // envelope 은 jsonb 다. PostgreSQL 이 정규화하므로 문자열 비교는 틀린 방법이다.
            JsonNode envelope = readEnvelope((String) row.get("envelope"));
            assertThat(envelope.get("producer").asText()).isEqualTo("business-support");
            assertThat(envelope.get("payload").get("hasExclusions").asBoolean()).isTrue();

            // 민감정보는 문자열 전체에서 확인한다 — 어느 키에 숨어 있든 나가면 안 된다.
            assertThat((String) row.get("envelope"))
                    .as("부담보 상세(KCD 범위)는 이벤트에 싣지 않는다 — 건강정보 추론 가능")
                    .doesNotContain("M40-M54");
        }

        @Test
        @DisplayName("정정은 policy.corrected를 남긴다 — claims의 재심사 트리거")
        void shouldAppendPolicyCorrected() {
            부담보계약저장();
            jdbc.update("DELETE FROM outbox_event");

            commandService.correctExclusion(new PolicyCommandService.CorrectExclusionCommand(
                    POLICY_NO, 척추부담보, null, "착오 정정", "UW-0007", "UW-MGR"));

            var row = jdbc.queryForMap("""
                    SELECT event_type, envelope::text AS envelope
                    FROM outbox_event WHERE aggregate_id = ?
                    """, POLICY_NO.value());

            assertThat(row.get("event_type")).isEqualTo("policy.corrected");
            JsonNode payload = readEnvelope((String) row.get("envelope")).get("payload");
            assertThat(payload.get("scopeValidFrom").asText()).isEqualTo("2026-01-01");
            assertThat(payload.get("approvedBy").asText())
                    .as("과거를 바꾸는 행위이므로 승인자가 이벤트에도 남는다")
                    .isEqualTo("UW-MGR");
            assertThat(payload.get("previousSnapshotVersion").asInt()).isEqualTo(1);
            assertThat(payload.get("newSnapshotVersion").asInt())
                    .as("정정만 스냅샷 버전을 올린다")
                    .isEqualTo(2);
        }
    }

    private JsonNode readEnvelope(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("봉투 JSON을 읽을 수 없습니다: " + json, e);
        }
    }

    /** 픽스처의 담보 코드 참조 (테스트 가독성용) */
    private static final class PolicyFixturesRef {
        static com.insurance.policy.domain.policy.CoverageCode 급여입원코드() {
            return com.insurance.policy.domain.policy.CoverageCode.of("COV-INPT-COVERED");
        }

        static com.insurance.policy.domain.policy.CoverageCode 급여통원코드() {
            return com.insurance.policy.domain.policy.CoverageCode.of("COV-OUTP-COVERED");
        }

        static com.insurance.policy.domain.policy.CoverageCode 비급여통원코드() {
            return com.insurance.policy.domain.policy.CoverageCode.of("COV-OUTP-UNCOVERED");
        }
    }
}
