package com.insurance.policy.domain.policy;

import com.insurance.policy.domain.shared.vo.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테스트용 계약 조립기.
 *
 * <p>설계 문서의 4세대 실손 예시를 그대로 만든다. claims의 골든 케이스와
 * 같은 계약을 쓰기 위한 것이므로 수치를 임의로 바꾸지 말 것.
 *
 * @see docs/design/05-api.md §1.2
 */
public final class PolicyFixtures {

    public static final PolicyNo POLICY_NO = PolicyNo.of("P2026-0001234");
    public static final LocalDate 계약일 = LocalDate.of(2026, 1, 1);
    public static final LocalDate 만기일 = LocalDate.of(2031, 1, 1);
    public static final LocalDate 사고일 = LocalDate.of(2026, 3, 14);

    public static final Instant 최초기록 = Instant.parse("2026-01-01T00:00:00Z");
    public static final Instant 청구접수 = Instant.parse("2026-04-02T01:15:00Z");
    public static final Instant 정정시점 = Instant.parse("2026-05-20T02:00:00Z");
    public static final Instant 재심사 = Instant.parse("2026-06-01T00:00:00Z");

    public static final CoverageCode 급여입원 = CoverageCode.of("COV-INPT-COVERED");
    public static final CoverageCode 급여통원 = CoverageCode.of("COV-OUTP-COVERED");
    public static final CoverageCode 비급여통원 = CoverageCode.of("COV-OUTP-UNCOVERED");

    public static final ExclusionId 척추부담보 = ExclusionId.of("EXC-2026-000045");

    private PolicyFixtures() {
    }

    public static ProductRef product() {
        return new ProductRef("MED-INDEM-G4", "4세대 실손의료보험", Generation.of("G4"));
    }

    public static InsuredInfo insured() {
        return new InsuredInfo(InsuredRef.of("CI-a1b2c3d4"), 1988, InsuredInfo.Relation.SELF);
    }

    public static PolicyPeriod period() {
        return new PolicyPeriod(계약일, 만기일);
    }

    /** 급여 입원 — 정률 20%, 최소공제 없음, 연간 5천만원 */
    public static Coverage 급여입원담보() {
        return Coverage.create(급여입원, "급여 입원의료비", BenefitCategory.COVERED,
                Set.of(TreatmentType.INPATIENT), Money.ofWon(50_000_000L),
                CoverageTerms.inpatient(new BigDecimal("0.20"), 50_000_000L),
                null, 계약일, 만기일, 최초기록);
    }

    /** 급여 통원 — 정률 20%, 종별 최소공제 (상급종합 2만원), 회당 20만원, 연 100회 */
    public static Coverage 급여통원담보() {
        return Coverage.create(급여통원, "급여 통원의료비", BenefitCategory.COVERED,
                Set.of(TreatmentType.OUTPATIENT), Money.ofWon(200_000L),
                CoverageTerms.outpatient(new BigDecimal("0.20"),
                        Map.of(InstitutionGrade.CLINIC, 10_000L,
                                InstitutionGrade.HOSPITAL, 15_000L,
                                InstitutionGrade.GENERAL, 15_000L,
                                InstitutionGrade.TERTIARY, 20_000L),
                        200_000L, 100),
                null, 계약일, 만기일, 최초기록);
    }

    /** 비급여 통원 — 정률 30%, 최소공제 3만원, 회당 20만원, 연 100회 */
    public static Coverage 비급여통원담보() {
        return Coverage.create(비급여통원, "비급여 통원의료비", BenefitCategory.UNCOVERED,
                Set.of(TreatmentType.OUTPATIENT), Money.ofWon(200_000L),
                CoverageTerms.flatDeductible(new BigDecimal("0.30"), 30_000L, 200_000L, 100),
                null, 계약일, 만기일, 최초기록);
    }

    /** 척추 부담보 — 언더라이팅 결과. 2년 뒤 claims의 D-POL-004 부지급 근거가 된다 */
    public static Exclusion 척추부담보조건() {
        return Exclusion.create(척추부담보, ExclusionType.BODY_PART, "척추 및 그 부속기관",
                List.of(KcdRange.parse("M40-M54")), "언더라이팅 부담보", "U-2026-0012",
                계약일, 만기일, 최초기록);
    }

    /** 부담보가 붙은 정상 계약 */
    public static Policy 부담보계약() {
        return Policy.issue(POLICY_NO, product(), "HOLDER-1", insured(), period(),
                계약일, 계약일,
                List.of(급여입원담보(), 급여통원담보(), 비급여통원담보()),
                List.of(척추부담보조건()),
                "A2026-0005678", "U-2026-0012", 최초기록);
    }

    /** 부담보 없는 정상 계약 */
    public static Policy 표준계약() {
        return Policy.issue(POLICY_NO, product(), "HOLDER-1", insured(), period(),
                계약일, 계약일,
                List.of(급여입원담보(), 급여통원담보(), 비급여통원담보()),
                List.of(),
                "A2026-0005678", "U-2026-0013", 최초기록);
    }

    /** 담보만 지정해 계약을 만든다. */
    public static Policy 계약(List<Coverage> coverages, List<Exclusion> exclusions) {
        return Policy.issue(POLICY_NO, product(), "HOLDER-1", insured(), period(),
                계약일, 계약일, new ArrayList<>(coverages), new ArrayList<>(exclusions),
                "A2026-0005678", "U-2026-0012", 최초기록);
    }
}
