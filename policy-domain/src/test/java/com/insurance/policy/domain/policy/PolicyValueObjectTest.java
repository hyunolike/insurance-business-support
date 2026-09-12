package com.insurance.policy.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurance.policy.domain.shared.vo.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("계약 도메인 값 객체")
class PolicyValueObjectTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2031, 1, 1);

    @Nested
    @DisplayName("PolicyNo")
    class PolicyNoSpec {

        @Test
        @DisplayName("연도 + 시퀀스로 생성한다")
        void shouldGenerateFromSequence() {
            assertThat(PolicyNo.of(2026, 1234L)).hasToString("P2026-0001234");
            assertThat(PolicyNo.of(2026, 1234L).value()).isEqualTo("P2026-0001234");
        }

        @ParameterizedTest
        @DisplayName("형식이 틀리면 거부한다")
        @ValueSource(strings = {"", "P2026-1234", "2026-0001234", "P26-0001234", "X2026-0001234"})
        void shouldRejectMalformed(String value) {
            assertThatThrownBy(() -> PolicyNo.of(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("범위를 벗어난 연도·시퀀스는 거부한다")
        void shouldRejectOutOfRange() {
            assertThatThrownBy(() -> PolicyNo.of(999, 1L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("연도");
            assertThatThrownBy(() -> PolicyNo.of(2026, 10_000_000L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("일련번호");
            assertThatThrownBy(() -> PolicyNo.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("동등성")
        void shouldCompareByValue() {
            var a = PolicyNo.of("P2026-0001234");
            assertThat(a).isEqualTo(PolicyNo.of("P2026-0001234"))
                    .hasSameHashCodeAs(PolicyNo.of("P2026-0001234"));
            assertThat(a).isNotEqualTo(PolicyNo.of("P2026-0009999"));
            assertThat(a).isNotEqualTo("P2026-0001234").isNotEqualTo(null).isEqualTo(a);
        }
    }

    @Nested
    @DisplayName("InsuredRef")
    class InsuredRefSpec {

        @Test
        @DisplayName("★ 주민등록번호로 보이는 값을 거부한다")
        void shouldRejectResidentRegistrationNumber() {
            assertThatThrownBy(() -> InsuredRef.of("900101-1234567"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("주민등록번호");
            assertThatThrownBy(() -> InsuredRef.of("9001011234567"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("주민등록번호");
        }

        @Test
        @DisplayName("CI·내부 고객키는 허용한다")
        void shouldAcceptOpaqueIdentifiers() {
            assertThat(InsuredRef.of("CI-a1b2c3d4").value()).isEqualTo("CI-a1b2c3d4");
            assertThat(InsuredRef.of("  CUST-0001  ").value()).isEqualTo("CUST-0001");
        }

        @Test
        @DisplayName("빈 값은 거부한다")
        void shouldRejectBlank() {
            assertThatThrownBy(() -> InsuredRef.of("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> InsuredRef.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("동등성")
        void shouldCompareByValue() {
            var a = InsuredRef.of("CI-1");
            assertThat(a).isEqualTo(InsuredRef.of("CI-1")).hasSameHashCodeAs(InsuredRef.of("CI-1"));
            assertThat(a).isNotEqualTo(InsuredRef.of("CI-2")).isNotEqualTo("CI-1")
                    .isNotEqualTo(null).isEqualTo(a);
            assertThat(a).hasToString("CI-1");
        }
    }

    @Nested
    @DisplayName("Generation")
    class GenerationSpec {

        @Test
        @DisplayName("enum이 아니라 VO다 — 새 세대가 나와도 코드 변경이 필요 없다")
        void shouldAcceptAnyValidGeneration() {
            assertThat(Generation.of("G4").code()).isEqualTo("G4");
            assertThat(Generation.of("g4").code()).isEqualTo("G4");
            assertThat(Generation.of("G5").code())
                    .as("5세대가 나와도 배포 없이 받을 수 있다")
                    .isEqualTo("G5");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "G", "G0", "G10", "4", "X4"})
        @DisplayName("형식이 틀리면 거부한다")
        void shouldRejectMalformed(String code) {
            assertThatThrownBy(() -> Generation.of(code))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("동등성")
        void shouldCompareByValue() {
            var g4 = Generation.of("G4");
            assertThat(g4).isEqualTo(Generation.of("G4")).hasSameHashCodeAs(Generation.of("G4"));
            assertThat(g4).isNotEqualTo(Generation.of("G3")).isNotEqualTo("G4")
                    .isNotEqualTo(null).isEqualTo(g4);
            assertThat(g4).hasToString("G4");
            assertThatThrownBy(() -> Generation.of(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("CoverageCode · ExclusionId")
    class IdentifierSpec {

        @Test
        @DisplayName("담보코드는 정규화된다")
        void shouldNormalizeCoverageCode() {
            assertThat(CoverageCode.of("  cov-inpt  ").value()).isEqualTo("COV-INPT");
        }

        @Test
        @DisplayName("담보코드 길이 제한")
        void shouldRejectInvalidCoverageCode() {
            assertThatThrownBy(() -> CoverageCode.of("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CoverageCode.of("X".repeat(33)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CoverageCode.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("담보코드 동등성")
        void coverageCodeEquality() {
            var a = CoverageCode.of("COV-1");
            assertThat(a).isEqualTo(CoverageCode.of("cov-1")).hasSameHashCodeAs(CoverageCode.of("COV-1"));
            assertThat(a).isNotEqualTo(CoverageCode.of("COV-2")).isNotEqualTo("COV-1")
                    .isNotEqualTo(null).isEqualTo(a);
            assertThat(a).hasToString("COV-1");
        }

        @Test
        @DisplayName("부담보 식별자는 버전 간 공유된다")
        void shouldGenerateExclusionId() {
            assertThat(ExclusionId.of(2026, 45L)).hasToString("EXC-2026-000045");
            assertThat(ExclusionId.of("EXC-2026-000045").value()).isEqualTo("EXC-2026-000045");
        }

        @Test
        @DisplayName("부담보 식별자 형식 검증")
        void shouldRejectInvalidExclusionId() {
            assertThatThrownBy(() -> ExclusionId.of("EXC-2026-45"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ExclusionId.of(2026, 1_000_000L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ExclusionId.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("부담보 식별자 동등성")
        void exclusionIdEquality() {
            var a = ExclusionId.of("EXC-2026-000045");
            assertThat(a).isEqualTo(ExclusionId.of("EXC-2026-000045"))
                    .hasSameHashCodeAs(ExclusionId.of("EXC-2026-000045"));
            assertThat(a).isNotEqualTo(ExclusionId.of("EXC-2026-000046"))
                    .isNotEqualTo("EXC-2026-000045").isNotEqualTo(null).isEqualTo(a);
        }
    }

    @Nested
    @DisplayName("PolicyPeriod")
    class PolicyPeriodSpec {

        @Test
        @DisplayName("종료일은 배타적이다 — 만기일 당일 사고는 기간 밖")
        void shouldTreatEndExclusive() {
            var period = new PolicyPeriod(FROM, TO);
            assertThat(period.covers(FROM)).isTrue();
            assertThat(period.covers(TO.minusDays(1))).isTrue();
            assertThat(period.covers(TO)).isFalse();
            assertThat(period.covers(FROM.minusDays(1))).isFalse();
        }

        @Test
        @DisplayName("시작일이 종료일보다 뒤면 거부한다")
        void shouldRejectInverted() {
            assertThatThrownBy(() -> new PolicyPeriod(TO, FROM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PolicyPeriod(FROM, FROM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PolicyPeriod(null, TO))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("CoverageTerms")
    class CoverageTermsSpec {

        @Test
        @DisplayName("입원은 최소공제금액이 없다")
        void inpatientHasNoMinDeductible() {
            var terms = CoverageTerms.inpatient(new BigDecimal("0.20"), 50_000_000L);
            assertThat(terms.minDeductibleFor(InstitutionGrade.TERTIARY)).isNull();
            assertThat(terms.annualLimit()).isEqualTo(50_000_000L);
        }

        @Test
        @DisplayName("통원은 요양기관 종별로 최소공제금액이 다르다")
        void outpatientDiffersByGrade() {
            var terms = CoverageTerms.outpatient(new BigDecimal("0.20"),
                    Map.of(InstitutionGrade.CLINIC, 10_000L,
                            InstitutionGrade.TERTIARY, 20_000L),
                    200_000L, 100);

            assertThat(terms.minDeductibleFor(InstitutionGrade.CLINIC)).isEqualTo(10_000L);
            assertThat(terms.minDeductibleFor(InstitutionGrade.TERTIARY)).isEqualTo(20_000L);
            assertThat(terms.minDeductibleFor(InstitutionGrade.HOSPITAL))
                    .as("표에 없는 종별은 미적용")
                    .isNull();
            assertThat(terms.minDeductibleFor(null)).isNull();
        }

        @Test
        @DisplayName("정액 최소공제금액은 종별과 무관하게 적용된다")
        void flatDeductibleAppliesToAllGrades() {
            var terms = CoverageTerms.flatDeductible(
                    new BigDecimal("0.30"), 30_000L, 200_000L, 100);

            assertThat(terms.minDeductibleFor(InstitutionGrade.CLINIC)).isEqualTo(30_000L);
            assertThat(terms.minDeductibleFor(InstitutionGrade.TERTIARY)).isEqualTo(30_000L);
        }

        @Test
        @DisplayName("정액과 종별표를 동시에 지정할 수 없다")
        void shouldRejectAmbiguousDeductible() {
            assertThatThrownBy(() -> new CoverageTerms(new BigDecimal("0.30"), 30_000L,
                    Map.of(InstitutionGrade.CLINIC, 10_000L), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("동시에");
        }

        @Test
        @DisplayName("잘못된 값을 거부한다")
        void shouldRejectInvalidValues() {
            assertThatThrownBy(() -> CoverageTerms.inpatient(new BigDecimal("1.5"), 1L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0~1");
            assertThatThrownBy(() -> CoverageTerms.inpatient(new BigDecimal("-0.1"), 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CoverageTerms.inpatient(null, 1L))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> CoverageTerms.flatDeductible(
                    new BigDecimal("0.3"), -1L, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최소공제");
            assertThatThrownBy(() -> new CoverageTerms(
                    new BigDecimal("0.3"), null, null, -1L, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("연간 한도");
            assertThatThrownBy(() -> new CoverageTerms(
                    new BigDecimal("0.3"), null, null, null, null, -1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("횟수");
        }
    }

    @Nested
    @DisplayName("Coverage · Exclusion 불변성")
    class TemporalEntities {

        @Test
        @DisplayName("담보는 진료구분을 최소 1개 가져야 한다")
        void coverageNeedsTreatmentType() {
            assertThatThrownBy(() -> Coverage.create(
                    CoverageCode.of("C1"), "담보", BenefitCategory.COVERED,
                    Set.of(), Money.ofWon(1L),
                    CoverageTerms.inpatient(new BigDecimal("0.2"), 1L),
                    null, FROM, TO, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("최소 1개");
        }

        @Test
        @DisplayName("면책기간 판정")
        void shouldJudgeWaitingPeriod() {
            var withWaiting = Coverage.create(CoverageCode.of("C1"), "담보",
                    BenefitCategory.COVERED, Set.of(TreatmentType.INPATIENT), Money.ofWon(1L),
                    CoverageTerms.inpatient(new BigDecimal("0.2"), 1L),
                    LocalDate.of(2026, 4, 1), FROM, TO, NOW);

            assertThat(withWaiting.waitingPeriodPassed(LocalDate.of(2026, 3, 31))).isFalse();
            assertThat(withWaiting.waitingPeriodPassed(LocalDate.of(2026, 4, 1))).isTrue();

            var noWaiting = PolicyFixtures.급여입원담보();
            assertThat(noWaiting.waitingPeriodPassed(FROM)).isTrue();
        }

        @Test
        @DisplayName("진료구분 적용 여부")
        void shouldMatchTreatmentType() {
            var coverage = PolicyFixtures.급여통원담보();
            assertThat(coverage.appliesTo(TreatmentType.OUTPATIENT)).isTrue();
            assertThat(coverage.appliesTo(TreatmentType.INPATIENT)).isFalse();
            assertThat(coverage.treatmentTypes()).containsExactly(TreatmentType.OUTPATIENT);
            assertThat(coverage.changeType()).isEqualTo(ChangeType.CREATE);
            assertThat(coverage.name()).isEqualTo("급여 통원의료비");
            assertThat(coverage.benefitCategory()).isEqualTo(BenefitCategory.COVERED);
            assertThat(coverage.insuredAmount()).isEqualTo(Money.ofWon(200_000L));
            assertThat(coverage.toString()).contains("COV-OUTP-COVERED");
        }

        @Test
        @DisplayName("구간 종료일은 시작일보다 뒤여야 한다")
        void shouldRejectInvalidEnding() {
            var coverage = PolicyFixtures.급여입원담보();
            assertThatThrownBy(() -> coverage.closedAt(FROM, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("닫힌 구간은 새 기록이다 — 기존 기록을 수정하지 않는다")
        void closedIntervalIsANewRecord() {
            var original = PolicyFixtures.급여입원담보();
            var closed = original.closedAt(LocalDate.of(2026, 6, 1), NOW);

            assertThat(original.validTo())
                    .as("원본은 그대로다. 줄이면 시점 재현성이 깨진다")
                    .isEqualTo(PolicyFixtures.만기일);
            assertThat(closed.validTo()).isEqualTo(LocalDate.of(2026, 6, 1));
            assertThat(closed.changeType()).isEqualTo(ChangeType.ENDORSEMENT);
            assertThat(closed.supersededAt()).isNull();
        }

        @Test
        @DisplayName("대체 사유를 구분한다 — 정정만 스냅샷 버전을 올린다")
        void shouldDistinguishSupersessionReason() {
            var byCorrection = PolicyFixtures.척추부담보조건().superseded(NOW, true);
            var byEndorsement = PolicyFixtures.급여입원담보().superseded(NOW, false);

            assertThat(byCorrection.supersededByCorrection()).isTrue();
            assertThat(byEndorsement.supersededByCorrection())
                    .as("변경이 구간을 닫은 것은 과거 사실이 틀렸다는 뜻이 아니다")
                    .isFalse();
            assertThat(byCorrection.isCurrentRecord()).isFalse();
        }

        @Test
        @DisplayName("이미 대체된 기록은 다시 대체할 수 없다")
        void shouldRejectDoubleSupersede() {
            var coverage = PolicyFixtures.급여입원담보().superseded(NOW, true);
            assertThatThrownBy(() -> coverage.superseded(NOW, true))
                    .isInstanceOf(IllegalStateException.class);

            var exclusion = PolicyFixtures.척추부담보조건().superseded(NOW, true);
            assertThatThrownBy(() -> exclusion.superseded(NOW, true))
                    .isInstanceOf(IllegalStateException.class);

            var version = PolicyVersion.create(PolicyStatus.IN_FORCE, FROM, TO, NOW, "SYSTEM")
                    .superseded(NOW, true);
            assertThatThrownBy(() -> version.superseded(NOW, true))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("부담보는 KCD 범위를 최소 1개 가져야 한다 — 없으면 claims가 판정할 수 없다")
        void exclusionNeedsKcdRange() {
            assertThatThrownBy(() -> Exclusion.create(
                    ExclusionId.of("EXC-2026-000001"), ExclusionType.BODY_PART, "척추",
                    List.of(), "사유", "U-1", FROM, TO, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("최소 1개");
        }

        @Test
        @DisplayName("부담보 접근자")
        void exclusionAccessors() {
            var exclusion = PolicyFixtures.척추부담보조건();
            assertThat(exclusion.type()).isEqualTo(ExclusionType.BODY_PART);
            assertThat(exclusion.reason()).isEqualTo("언더라이팅 부담보");
            assertThat(exclusion.kcdRanges()).hasSize(1);
            assertThat(exclusion.changeType()).isEqualTo(ChangeType.CREATE);
            assertThat(exclusion.toString()).contains("척추");
        }

        @Test
        @DisplayName("계약 상태 이력 접근자")
        void policyVersionAccessors() {
            var version = PolicyVersion.endorsement(PolicyStatus.GRACE, FROM, TO, NOW,
                    "미납", "BATCH");
            assertThat(version.status()).isEqualTo(PolicyStatus.GRACE);
            assertThat(version.changeType()).isEqualTo(ChangeType.ENDORSEMENT);
            assertThat(version.reason()).isEqualTo("미납");
            assertThat(version.actorRef()).isEqualTo("BATCH");
            assertThat(version.toString()).contains("GRACE");
            assertThatThrownBy(() -> version.closedAt(FROM, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("InsuredInfo · BenefitYear")
    class SnapshotValueObjects {

        @Test
        @DisplayName("출생연도만 담는다 — 전체 생년월일은 심사에 불필요하다")
        void shouldCarryOnlyBirthYear() {
            var info = new InsuredInfo(InsuredRef.of("CI-1"), 1988, InsuredInfo.Relation.SELF);
            assertThat(info.birthYear()).isEqualTo(1988);
            assertThat(info.relationToHolder().label()).isEqualTo("본인");
        }

        @Test
        @DisplayName("출생연도 범위를 검증한다")
        void shouldValidateBirthYear() {
            assertThatThrownBy(() -> new InsuredInfo(
                    InsuredRef.of("CI-1"), 1800, InsuredInfo.Relation.SELF))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InsuredInfo(null, 1988, InsuredInfo.Relation.SELF))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("보장연도는 응당일에 리셋된다")
        void benefitYearResetsOnAnniversary() {
            var effectiveDate = LocalDate.of(2026, 3, 1);

            var year1 = BenefitYear.of(effectiveDate, LocalDate.of(2026, 3, 1));
            assertThat(year1.from()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(year1.to()).isEqualTo(LocalDate.of(2027, 3, 1));
            assertThat(year1.label()).contains("1년차");
            assertThat(year1.covers(LocalDate.of(2027, 2, 28))).isTrue();
            assertThat(year1.covers(LocalDate.of(2027, 3, 1))).isFalse();

            var year2 = BenefitYear.of(effectiveDate, LocalDate.of(2027, 3, 1));
            assertThat(year2.from()).isEqualTo(LocalDate.of(2027, 3, 1));
            assertThat(year2.label()).contains("2년차");

            var year3 = BenefitYear.of(effectiveDate, LocalDate.of(2028, 12, 31));
            assertThat(year3.from()).isEqualTo(LocalDate.of(2028, 3, 1));
        }

        @Test
        @DisplayName("2월 29일 계약도 안전하게 계산된다")
        void shouldHandleLeapDayAnniversary() {
            var leapDay = LocalDate.of(2024, 2, 29);
            var year = BenefitYear.of(leapDay, LocalDate.of(2025, 6, 1));

            assertThat(year.from())
                    .as("윤일이 없는 해는 2월 28일로 맞춰진다")
                    .isEqualTo(LocalDate.of(2025, 2, 28));
        }

        @Test
        @DisplayName("책임개시일 이전은 계산할 수 없다")
        void shouldRejectBeforeEffectiveDate() {
            assertThatThrownBy(() -> BenefitYear.of(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("앞섭니다");
            assertThatThrownBy(() -> BenefitYear.of(null, FROM))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BenefitYear("x", TO, FROM))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Policy 생성 규칙")
    class PolicyCreation {

        @Test
        @DisplayName("★ 책임개시일은 승낙일과 초회납입일 중 늦은 날이다")
        void effectiveDateIsLaterOfAcceptanceAndFirstPremium() {
            var 승낙먼저 = Policy.issue(PolicyFixtures.POLICY_NO, PolicyFixtures.product(), "H",
                    PolicyFixtures.insured(), PolicyFixtures.period(),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5),
                    List.of(PolicyFixtures.급여입원담보()), List.of(), "A", "U", NOW);
            assertThat(승낙먼저.effectiveDate())
                    .as("보험료를 늦게 냈으면 그날부터 책임이 개시된다")
                    .isEqualTo(LocalDate.of(2026, 1, 5));

            var 납입먼저 = Policy.issue(PolicyFixtures.POLICY_NO, PolicyFixtures.product(), "H",
                    PolicyFixtures.insured(), PolicyFixtures.period(),
                    LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 1),
                    List.of(PolicyFixtures.급여입원담보()), List.of(), "A", "U", NOW);
            assertThat(납입먼저.effectiveDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        }

        @Test
        @DisplayName("담보가 없으면 계약이 성립하지 않는다")
        void shouldRequireAtLeastOneCoverage() {
            assertThatThrownBy(() -> Policy.issue(PolicyFixtures.POLICY_NO,
                    PolicyFixtures.product(), "H", PolicyFixtures.insured(),
                    PolicyFixtures.period(), FROM, FROM, List.of(), List.of(), "A", "U", NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("최소 1개의 담보");
        }

        @Test
        @DisplayName("책임개시일이 보험기간 밖이면 거부한다")
        void shouldRejectEffectiveDateOutsidePeriod() {
            assertThatThrownBy(() -> Policy.issue(PolicyFixtures.POLICY_NO,
                    PolicyFixtures.product(), "H", PolicyFixtures.insured(),
                    PolicyFixtures.period(),
                    LocalDate.of(2031, 6, 1), LocalDate.of(2031, 6, 1),
                    List.of(PolicyFixtures.급여입원담보()), List.of(), "A", "U", NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("보험기간 밖");
        }

        @Test
        @DisplayName("계약 성립은 policy.issued를 발행한다")
        void shouldEmitPolicyIssued() {
            var 계약 = PolicyFixtures.부담보계약();

            assertThat(계약.pullEvents()).singleElement().satisfies(e -> {
                assertThat(e.eventType()).isEqualTo("policy.issued");
                assertThat(e.aggregateId()).isEqualTo(PolicyFixtures.POLICY_NO.value());
            });
        }

        @Test
        @DisplayName("접근자")
        void accessors() {
            var 계약 = PolicyFixtures.부담보계약();
            assertThat(계약.holderRef()).isEqualTo("HOLDER-1");
            assertThat(계약.applicationNo()).isEqualTo("A2026-0005678");
            assertThat(계약.uwCaseNo()).isEqualTo("U-2026-0012");
            assertThat(계약.versions()).hasSize(1);
            assertThat(계약.allCoverages()).hasSize(3);
            assertThat(계약.allExclusions()).hasSize(1);
            assertThat(계약.product().generation().code()).isEqualTo("G4");
            assertThat(계약.toString()).contains("P2026-0001234");
        }

        @Test
        @DisplayName("존재하지 않는 담보는 변경할 수 없다")
        void shouldRejectEndorsingUnknownCoverage() {
            var 계약 = PolicyFixtures.표준계약();
            assertThatThrownBy(() -> 계약.endorseCoverage(CoverageCode.of("COV-NONE"),
                    CoverageTerms.inpatient(new BigDecimal("0.2"), 1L), Money.ofWon(1L),
                    LocalDate.of(2026, 6, 1), "사유", "CS", Instant.parse("2026-06-01T00:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("유효한 담보가 없습니다");
        }

        @Test
        @DisplayName("존재하지 않는 부담보는 정정할 수 없다")
        void shouldRejectCorrectingUnknownExclusion() {
            var 계약 = PolicyFixtures.표준계약();
            assertThatThrownBy(() -> 계약.correctExclusion(
                    ExclusionId.of("EXC-2026-999999"), null, "사유", "A", "B", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
