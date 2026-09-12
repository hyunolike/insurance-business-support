package com.insurance.policy.domain.policy;

import static com.insurance.policy.domain.policy.PolicyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurance.policy.domain.policy.event.PolicyCorrected;
import com.insurance.policy.domain.policy.event.PolicyEndorsed;
import com.insurance.policy.domain.policy.exception.SnapshotNotAvailableException;
import com.insurance.policy.domain.shared.vo.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ★ 시점 재현성 — 이 시스템의 절대 요구사항.
 *
 * <p>여기가 깨지면 claims의 심사 근거가 무너지고, 두 레포를 나눈 설계 전체가 의미를 잃는다.
 *
 * <p>DB도 Spring도 없이 돈다. 도메인이 순수하기 때문이다.
 */
@DisplayName("Policy.snapshotAsOf — 시점 재현성")
class PolicySnapshotTest {

    @Nested
    @DisplayName("기본 동작")
    class Basics {

        @Test
        @DisplayName("사고일 시점의 계약 상태를 굳힌다")
        void shouldFreezeStateAtAccidentDate() {
            var snapshot = 부담보계약().snapshotAsOf(사고일, 청구접수);

            assertThat(snapshot.policyNo()).isEqualTo(POLICY_NO);
            assertThat(snapshot.asOf()).isEqualTo(사고일);
            assertThat(snapshot.policyStatusAsOf()).isEqualTo(PolicyStatus.IN_FORCE);
            assertThat(snapshot.effectiveDate()).isEqualTo(계약일);
            assertThat(snapshot.withinPolicyPeriod()).isTrue();
            assertThat(snapshot.snapshotVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("담보와 부담보가 담긴다")
        void shouldIncludeCoveragesAndExclusions() {
            var snapshot = 부담보계약().snapshotAsOf(사고일, 청구접수);

            assertThat(snapshot.coverages())
                    .extracting(c -> c.coverageCode().value())
                    .containsExactly("COV-INPT-COVERED", "COV-OUTP-COVERED", "COV-OUTP-UNCOVERED");
            assertThat(snapshot.exclusions())
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.target()).isEqualTo("척추 및 그 부속기관");
                        assertThat(e.uwCaseNo())
                                .as("claims가 부지급 근거를 인수심사까지 역추적하는 경로")
                                .isEqualTo("U-2026-0012");
                    });
        }

        @Test
        @DisplayName("보장연도는 계약 응당일 기준이다 — 달력연도가 아니다")
        void shouldComputeBenefitYearFromAnniversary() {
            var 계약 = Policy.issue(POLICY_NO, product(), "H", insured(),
                    new PolicyPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2031, 3, 1)),
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1),
                    List.of(급여입원담보().withTerms(
                            CoverageTerms.inpatient(new BigDecimal("0.20"), 50_000_000L),
                            Money.ofWon(50_000_000L),
                            LocalDate.of(2026, 3, 1), LocalDate.of(2031, 3, 1), 최초기록)),
                    List.of(), "A", "U", 최초기록);

            var 첫해 = 계약.snapshotAsOf(LocalDate.of(2027, 2, 28), 재심사).benefitYear();
            assertThat(첫해.from()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(첫해.to()).isEqualTo(LocalDate.of(2027, 3, 1));

            var 둘째해 = 계약.snapshotAsOf(LocalDate.of(2027, 3, 1), 재심사).benefitYear();
            assertThat(둘째해.from())
                    .as("응당일에 한도가 리셋된다. 달력연도였다면 1월 1일이었을 것")
                    .isEqualTo(LocalDate.of(2027, 3, 1));
        }

        @Test
        @DisplayName("계약 생성 이전 시점은 재현할 수 없다")
        void shouldRejectAsOfBeforePolicyExists() {
            var 계약 = 부담보계약();

            assertThatThrownBy(() -> 계약.snapshotAsOf(LocalDate.of(2025, 12, 1), 청구접수))
                    .isInstanceOf(SnapshotNotAvailableException.class);
        }

        @Test
        @DisplayName("기록되기 전 knownAt으로는 재현할 수 없다")
        void shouldRejectKnownAtBeforeRecording() {
            var 계약 = 부담보계약();

            assertThatThrownBy(() ->
                    계약.snapshotAsOf(사고일, Instant.parse("2025-12-01T00:00:00Z")))
                    .isInstanceOf(SnapshotNotAvailableException.class);
        }
    }

    @Nested
    @DisplayName("★ 소급 정정 — 두 시간축이 필요한 이유")
    class Correction {

        @Test
        @DisplayName("정정 후에도 정정 전 판단을 재현할 수 있다")
        void shouldReproducePastJudgementAfterCorrection() {
            var 계약 = 부담보계약();

            // 4월 2일: 청구 접수. 이 시점에 우리는 부담보가 있다고 알고 있었다.
            var 접수시점스냅샷 = 계약.snapshotAsOf(사고일, 청구접수);
            assertThat(접수시점스냅샷.exclusions()).hasSize(1);
            assertThat(접수시점스냅샷.exclusionsMatching("M51.2"))
                    .as("요추 추간판 장애가 척추 부담보에 저촉 → claims가 D-POL-004 부지급")
                    .hasSize(1);

            // 5월 20일: 착오 발견. 부담보는 애초에 잘못 입력된 것이었다.
            계약.correctExclusion(척추부담보, null,
                    "부담보 조건 착오 입력 정정", "UW-0007", "UW-MANAGER-003", 정정시점);

            // 6월 1일 재심사: 지금 아는 진실로는 부담보가 없다
            var 재심사스냅샷 = 계약.snapshotAsOf(사고일, 재심사);
            assertThat(재심사스냅샷.exclusions())
                    .as("정정 후 조회 — 부담보 없음")
                    .isEmpty();

            // ★ 그런데 4월 판단도 여전히 재현된다
            var 다시본접수시점 = 계약.snapshotAsOf(사고일, 청구접수);
            assertThat(다시본접수시점.exclusions())
                    .as("4월의 부지급 근거를 설명할 수 있어야 한다. "
                            + "유효시간만 있었다면 이게 불가능하고, 우리가 틀렸다는 것조차 증명 못 한다")
                    .hasSize(1);

            // 정정이 스냅샷 버전을 올린다
            assertThat(다시본접수시점.snapshotVersion()).isEqualTo(1);
            assertThat(재심사스냅샷.snapshotVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("정정은 policy.corrected를 발행한다 — claims의 재심사 트리거")
        void shouldEmitPolicyCorrected() {
            var 계약 = 부담보계약();
            계약.pullEvents();   // policy.issued 비우기

            계약.correctExclusion(척추부담보, null, "착오 정정", "UW-0007", "UW-MANAGER-003", 정정시점);

            assertThat(계약.pullEvents())
                    .singleElement()
                    .isInstanceOfSatisfying(PolicyCorrected.class, e -> {
                        assertThat(e.eventType()).isEqualTo("policy.corrected");
                        assertThat(e.affectedElements()).containsExactly("EXCLUSION");
                        assertThat(e.scopeValidFrom()).isEqualTo(계약일);
                        assertThat(e.scopeValidTo()).isEqualTo(만기일);
                        assertThat(e.previousSnapshotVersion()).isEqualTo(1);
                        assertThat(e.newSnapshotVersion()).isEqualTo(2);
                        assertThat(e.approvedBy()).isEqualTo("UW-MANAGER-003");
                    });
        }

        @Test
        @DisplayName("정정은 요청자와 승인자가 달라야 한다 — 과거를 바꾸는 행위다")
        void shouldRequireSeparateApprover() {
            var 계약 = 부담보계약();

            assertThatThrownBy(() -> 계약.correctExclusion(척추부담보, null,
                    "착오 정정", "UW-0007", "UW-0007", 정정시점))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("요청자와 승인자가 달라야");
        }

        @Test
        @DisplayName("정정 사유·승인자가 없으면 거부한다")
        void shouldRequireReasonAndApprover() {
            var 계약 = 부담보계약();

            assertThatThrownBy(() ->
                    계약.correctExclusion(척추부담보, null, null, "UW-0007", "UW-MGR", 정정시점))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    계약.correctExclusion(척추부담보, null, "사유", "UW-0007", null, 정정시점))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("이미 정정된 부담보는 다시 정정할 수 없다")
        void shouldRejectDoubleCorrection() {
            var 계약 = 부담보계약();
            계약.correctExclusion(척추부담보, null, "착오", "UW-0007", "UW-MGR", 정정시점);

            assertThatThrownBy(() -> 계약.correctExclusion(척추부담보, null,
                    "또 정정", "UW-0007", "UW-MGR", 재심사))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("찾을 수 없습니다");
        }

        @Test
        @DisplayName("정정으로 부담보를 교체할 수도 있다 — 범위가 틀렸던 경우")
        void shouldReplaceExclusionOnCorrection() {
            var 계약 = 부담보계약();
            var 정정된부담보 = Exclusion.create(
                    ExclusionId.of("EXC-2026-000046"), ExclusionType.BODY_PART,
                    "요추부", List.of(KcdRange.parse("M50-M54")),
                    "부담보 범위 정정", "U-2026-0012", 계약일, 만기일, 정정시점);

            계약.correctExclusion(척추부담보, 정정된부담보,
                    "부담보 범위가 과도하게 넓게 입력됨", "UW-0007", "UW-MGR", 정정시점);

            // 경추(M43)는 이제 저촉되지 않는다
            assertThat(계약.snapshotAsOf(사고일, 재심사).exclusionsMatching("M43.0")).isEmpty();
            // 요추(M51)는 여전히 저촉된다
            assertThat(계약.snapshotAsOf(사고일, 재심사).exclusionsMatching("M51.2")).hasSize(1);
            // 4월 시점에는 경추도 저촉됐었다
            assertThat(계약.snapshotAsOf(사고일, 청구접수).exclusionsMatching("M43.0")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("변경(Endorsement) — 과거를 바꾸지 않는다")
    class Endorsement {

        private static final LocalDate 변경일 = LocalDate.of(2026, 6, 1);
        private static final Instant 변경기록 = Instant.parse("2026-06-01T00:00:00Z");

        @Test
        @DisplayName("변경 후에도 과거 스냅샷은 그대로다")
        void shouldNotAffectPastSnapshots() {
            var 계약 = 표준계약();

            계약.endorseCoverage(비급여통원,
                    CoverageTerms.flatDeductible(new BigDecimal("0.30"), 30_000L, 300_000L, 100),
                    Money.ofWon(300_000L), 변경일, "고객 요청", "CS-001", 변경기록);

            // 사고일(3/14) 기준 — 변경 전 조건
            var 과거 = 계약.snapshotAsOf(사고일, 변경기록).coverages().stream()
                    .filter(c -> c.coverageCode().equals(비급여통원)).findFirst().orElseThrow();
            assertThat(과거.terms().perVisitLimit())
                    .as("변경은 과거를 바꾸지 않는다")
                    .isEqualTo(200_000L);

            // 변경 후 사고 — 새 조건
            var 현재 = 계약.snapshotAsOf(LocalDate.of(2026, 7, 1), 변경기록).coverages().stream()
                    .filter(c -> c.coverageCode().equals(비급여통원)).findFirst().orElseThrow();
            assertThat(현재.terms().perVisitLimit()).isEqualTo(300_000L);
        }

        @Test
        @DisplayName("변경은 스냅샷 버전을 올리지 않는다 — 과거가 그대로이므로")
        void shouldNotBumpSnapshotVersion() {
            var 계약 = 표준계약();
            계약.endorseCoverage(비급여통원,
                    CoverageTerms.flatDeductible(new BigDecimal("0.30"), 30_000L, 300_000L, 100),
                    Money.ofWon(300_000L), 변경일, "고객 요청", "CS-001", 변경기록);

            assertThat(계약.snapshotAsOf(사고일, 변경기록).snapshotVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("변경은 policy.endorsed를 발행한다")
        void shouldEmitPolicyEndorsed() {
            var 계약 = 표준계약();
            계약.pullEvents();

            계약.endorseCoverage(비급여통원,
                    CoverageTerms.flatDeductible(new BigDecimal("0.30"), 30_000L, 300_000L, 100),
                    Money.ofWon(300_000L), 변경일, "고객 요청", "CS-001", 변경기록);

            assertThat(계약.pullEvents())
                    .singleElement()
                    .isInstanceOfSatisfying(PolicyEndorsed.class, e -> {
                        assertThat(e.eventType()).isEqualTo("policy.endorsed");
                        assertThat(e.affectedCoverages()).containsExactly(비급여통원);
                        assertThat(e.effectiveFrom()).isEqualTo(변경일);
                    });
        }

        @Test
        @DisplayName("책임개시일 이전으로 변경할 수 없다 — 그건 정정이다")
        void shouldRejectBackdatedEndorsement() {
            var 계약 = 표준계약();

            assertThatThrownBy(() -> 계약.endorseCoverage(비급여통원,
                    CoverageTerms.flatDeductible(new BigDecimal("0.30"), 30_000L, 300_000L, 100),
                    Money.ofWon(300_000L), LocalDate.of(2025, 12, 1), "소급", "CS-001", 변경기록))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("정정(correction)을 쓰세요");
        }
    }

    @Test
    @DisplayName("같은 (asOf, knownAt)은 몇 번을 조회해도 같은 결과다")
    void shouldBeDeterministic() {
        var 계약 = 부담보계약();
        계약.correctExclusion(척추부담보, null, "착오", "UW-0007", "UW-MGR", 정정시점);

        var first = 계약.snapshotAsOf(사고일, 청구접수);
        for (int i = 0; i < 100; i++) {
            var again = 계약.snapshotAsOf(사고일, 청구접수);
            assertThat(again.policyStatusAsOf()).isEqualTo(first.policyStatusAsOf());
            assertThat(again.snapshotVersion()).isEqualTo(first.snapshotVersion());
            assertThat(again.coverages()).hasSameSizeAs(first.coverages());
            assertThat(again.exclusions()).hasSameSizeAs(first.exclusions());
        }
    }
}
