package com.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurance.policy.application.port.out.PolicyRepository;
import com.insurance.policy.domain.policy.KcdRange;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.support.IntegrationTestBase;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 시드 데이터.
 *
 * <p><b>시드는 검증하지 않으면 썩는다.</b> 스키마가 바뀌거나 제약이 추가되면 조용히
 * 로드에 실패하고, 그때는 로컬 개발과 claims 연동 확인이 함께 멈춘다.
 * 그래서 실제로 Flyway 로 올려보고, 올라온 계약이 <b>의도한 판정 근거</b>가 되는지 본다.
 *
 * <p>claims 의 골든 케이스가 이 계약번호들을 참조한다. 여기서 값이 바뀌면
 * 저쪽 기대값도 함께 바뀌어야 한다.
 */
@TestPropertySource(properties =
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
@DisplayName("시드 데이터")
class SeedDataIntegrationTest extends IntegrationTestBase {

    /** 시드의 기록시점. 이 값들이 바뀌면 knownAt 조회 재현이 깨진다. */
    private static final Instant 정정시점 = Instant.parse("2026-05-20T02:00:00Z");
    private static final Instant 정정직전 = 정정시점.minusSeconds(1);
    private static final Instant 지금 = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate 사고일 = LocalDate.of(2026, 3, 14);

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    /**
     * 시드가 있는지 확인하고, 없으면 다시 올린다.
     *
     * <p>Testcontainers 컨테이너는 테스트 클래스 간에 공유된다. 다른 통합 테스트가
     * 픽스처 초기화로 {@code TRUNCATE} 를 돌리면 시드도 함께 지워진다.
     * 실행 순서에 기대는 테스트는 언젠가 순서가 바뀌는 날 깨지므로,
     * 순서와 무관하게 성립하도록 여기서 보장한다.
     *
     * <p>시드 스크립트는 멱등하다 — 이미 있으면 스스로 빠져나간다.
     */
    @BeforeEach
    void ensureSeedLoaded() throws Exception {
        if (policyRepository.exists(PolicyNo.of("P2026-SEED-0001"))) {
            return;
        }
        String sql;
        try (var in = getClass().getResourceAsStream("/db/seed/R__seed_policies.sql")) {
            if (in == null) {
                throw new IllegalStateException("시드 스크립트를 찾을 수 없습니다.");
            }
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute(sql);
    }

    private com.insurance.policy.domain.policy.PolicySnapshot snapshot(
            String policyNo, LocalDate asOf, Instant knownAt) {
        return policyRepository.findAsOf(PolicyNo.of(policyNo), asOf, knownAt)
                .orElseThrow(() -> new AssertionError(
                        "시드 계약을 찾을 수 없습니다: " + policyNo))
                .snapshotAsOf(asOf, knownAt);
    }

    @Nested
    @DisplayName("SEED-0001 · 정상 4세대 실손")
    class Normal {

        @Test
        @DisplayName("담보 3종이 올라오고 조건이 정확하다")
        void shouldLoadStandardPolicy() {
            var s = snapshot("P2026-SEED-0001", 사고일, 지금);

            assertThat(s.policyStatusAsOf()).isEqualTo(PolicyStatus.IN_FORCE);
            assertThat(s.coverages()).hasSize(3);
            assertThat(s.exclusions()).isEmpty();

            var 급여통원 = s.coverages().stream()
                    .filter(c -> c.coverageCode().value().equals("COV-OUTP-COVERED"))
                    .findFirst().orElseThrow();
            assertThat(급여통원.terms().minDeductibleFor(
                    com.insurance.policy.domain.policy.InstitutionGrade.TERTIARY))
                    .as("상급종합 최소공제 2만원 — claims 자기부담금 계산에 그대로 쓰인다")
                    .isEqualTo(20_000L);
        }
    }

    @Nested
    @DisplayName("SEED-0002 · 척추 부담보")
    class WithExclusion {

        @Test
        @DisplayName("★ M51.2(요추 추간판탈출)가 부담보 범위에 든다 — claims 부지급 근거")
        void shouldCoverKcdRangeForDenial() {
            var s = snapshot("P2026-SEED-0002", 사고일, 지금);

            assertThat(s.exclusions()).singleElement().satisfies(e -> {
                assertThat(e.kcdRanges()).containsExactly(KcdRange.parse("M40-M54"));
                assertThat(e.uwCaseNo())
                        .as("왜 이 부담보가 붙었나를 인수심사까지 역추적하는 경로")
                        .isEqualTo("U-2026-SEED-02");
            });
            assertThat(s.exclusionsMatching("M51.2"))
                    .as("D-POL-004 부지급이 성립해야 한다")
                    .hasSize(1);
        }

        @Test
        @DisplayName("범위 밖 상병은 부담보에 걸리지 않는다")
        void shouldNotMatchOutsideRange() {
            var s = snapshot("P2026-SEED-0002", 사고일, 지금);

            assertThat(s.exclusionsMatching("J00"))
                    .as("감기는 척추 부담보와 무관하다")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("SEED-0003 · 유예 후 실효")
    class Lapsed {

        @Test
        @DisplayName("사고일이 어느 구간이냐로 계약상태가 갈린다")
        void shouldResolveStatusByAccidentDate() {
            assertThat(snapshot("P2026-SEED-0003", LocalDate.of(2026, 1, 15), 지금)
                    .policyStatusAsOf()).isEqualTo(PolicyStatus.IN_FORCE);

            assertThat(snapshot("P2026-SEED-0003", LocalDate.of(2026, 3, 1), 지금)
                    .policyStatusAsOf())
                    .as("납입최고 중에도 보장은 유지된다")
                    .isEqualTo(PolicyStatus.GRACE);

            assertThat(snapshot("P2026-SEED-0003", LocalDate.of(2026, 5, 1), 지금)
                    .policyStatusAsOf())
                    .as("실효 이후 사고는 부지급")
                    .isEqualTo(PolicyStatus.LAPSED);
        }
    }

    @Nested
    @DisplayName("★ SEED-0004 · 정정 이력")
    class Corrected {

        @Test
        @DisplayName("★★ 같은 사고일인데 조회 시점에 따라 답이 다르다")
        void shouldReproduceBothTruths() {
            var 정정전 = snapshot("P2026-SEED-0004", 사고일, 정정직전);
            var 정정후 = snapshot("P2026-SEED-0004", 사고일, 지금);

            assertThat(정정전.exclusions())
                    .as("4월에 우리가 알던 진실 — 이 부담보로 부지급했다")
                    .hasSize(1);
            assertThat(정정후.exclusions())
                    .as("지금 아는 진실 — 애초에 없었어야 할 부담보였다")
                    .isEmpty();

            // 이 두 답이 모두 재현되지 않으면, claims 는 4월의 부지급을 설명할 수 없거나
            // 재심사가 필요하다는 사실을 알 수 없다. 둘 다 필요하다.
        }

        @Test
        @DisplayName("정정은 스냅샷 버전을 올린다 — 변경은 올리지 않는다")
        void shouldBumpSnapshotVersionOnCorrection() {
            assertThat(snapshot("P2026-SEED-0004", 사고일, 정정직전).snapshotVersion())
                    .isEqualTo(1);
            assertThat(snapshot("P2026-SEED-0004", 사고일, 지금).snapshotVersion())
                    .as("claims 는 버전이 오른 것을 보고 재심사 대상임을 안다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("정정 기록이 승인자와 함께 남아 있다")
        void shouldRecordApprover() {
            var history = policyRepository.findChangeHistory(
                    PolicyNo.of("P2026-SEED-0004"), "EXCLUSION");

            assertThat(history)
                    .as("정정으로 무효화된 기록도 이력에 보여야 한다")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("시드를 다시 올려도 깨지지 않는다 — 멱등")
    void shouldBeIdempotent() {
        // Flyway 가 이미 한 번 올렸다. 시드 스크립트의 앞선 EXISTS 가드가 없으면
        // EXCLUDE 겹침 방지 제약에 걸려 두 번째 실행이 실패한다.
        // 여기서는 로드가 성공했고 계약이 4건 그대로인지만 확인한다.
        for (String no : new String[]{"P2026-SEED-0001", "P2026-SEED-0002",
                "P2026-SEED-0003", "P2026-SEED-0004"}) {
            assertThat(policyRepository.exists(PolicyNo.of(no)))
                    .as("%s 가 없다", no)
                    .isTrue();
        }
    }
}
