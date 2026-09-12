package com.insurance.policy.domain.policy;

import com.insurance.policy.domain.shared.Temporal;
import com.insurance.policy.domain.shared.vo.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 담보 — 시간축을 가진 사실.
 *
 * <p><b>세터가 없고 모든 필드가 final이다.</b> 이력 레코드를 수정하면 과거가 바뀌고,
 * 그러면 claims가 내린 판단을 재현할 수 없다. ArchUnit이 이 규칙을 검사한다.
 *
 * <ul>
 *   <li>변경(Endorsement) = 기존 구간을 {@link #endingAt}으로 닫고 새 인스턴스를 추가</li>
 *   <li>정정(Correction) = {@link #superseded}로 무효화하고 새 인스턴스를 추가</li>
 * </ul>
 */
public final class Coverage implements Temporal {

    private final CoverageCode coverageCode;
    private final String name;
    private final BenefitCategory benefitCategory;
    private final Set<TreatmentType> treatmentTypes;
    private final Money insuredAmount;
    private final CoverageTerms terms;
    private final LocalDate waitingPeriodEnd;

    private final LocalDate validFrom;
    private final LocalDate validTo;
    private final Instant recordedAt;
    private final Instant supersededAt;
    private final boolean supersededByCorrection;
    private final ChangeType changeType;

    private Coverage(CoverageCode coverageCode, String name, BenefitCategory benefitCategory,
                     Set<TreatmentType> treatmentTypes, Money insuredAmount, CoverageTerms terms,
                     LocalDate waitingPeriodEnd, LocalDate validFrom, LocalDate validTo,
                     Instant recordedAt, Instant supersededAt, boolean supersededByCorrection,
                     ChangeType changeType) {
        this.coverageCode = Objects.requireNonNull(coverageCode, "담보코드는 필수입니다.");
        this.name = Objects.requireNonNull(name, "담보명은 필수입니다.");
        this.benefitCategory = Objects.requireNonNull(benefitCategory, "보장구분은 필수입니다.");
        this.treatmentTypes = Set.copyOf(
                Objects.requireNonNull(treatmentTypes, "진료구분은 필수입니다."));
        if (this.treatmentTypes.isEmpty()) {
            throw new IllegalArgumentException("담보는 최소 1개의 진료구분을 가져야 합니다.");
        }
        this.insuredAmount = Objects.requireNonNull(insuredAmount, "보험가입금액은 필수입니다.");
        this.terms = Objects.requireNonNull(terms, "담보조건은 필수입니다.");
        this.waitingPeriodEnd = waitingPeriodEnd;
        this.validFrom = Objects.requireNonNull(validFrom, "유효시작일은 필수입니다.");
        this.validTo = Objects.requireNonNull(validTo, "유효종료일은 필수입니다.");
        if (!validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException(
                    "유효시작일은 종료일보다 앞서야 합니다: %s ~ %s".formatted(validFrom, validTo));
        }
        this.recordedAt = Objects.requireNonNull(recordedAt, "기록시각은 필수입니다.");
        this.supersededAt = supersededAt;
        this.supersededByCorrection = supersededByCorrection;
        this.changeType = Objects.requireNonNull(changeType, "변경유형은 필수입니다.");
    }

    public static Coverage create(CoverageCode coverageCode, String name,
                                  BenefitCategory benefitCategory,
                                  Set<TreatmentType> treatmentTypes, Money insuredAmount,
                                  CoverageTerms terms, LocalDate waitingPeriodEnd,
                                  LocalDate validFrom, LocalDate validTo, Instant recordedAt) {
        return new Coverage(coverageCode, name, benefitCategory, treatmentTypes, insuredAmount,
                terms, waitingPeriodEnd, validFrom, validTo, recordedAt, null, false,
                ChangeType.CREATE);
    }

    /**
     * 변경(Endorsement)으로 닫힌 구간을 <b>새 기록으로</b> 만든다.
     *
     * <p>기존 행의 {@code validTo}를 줄이지 않는다. 줄이면 시점 재현성이 깨진다 —
     * 변경 이전 {@code knownAt}으로 미래 날짜를 조회했을 때, 축소된 구간은 그 날짜를
     * 덮지 않고 새 구간은 아직 기록되지 않아 <b>아무것도 반환되지 않는다.</b>
     * 같은 {@code (asOf, knownAt)}이 다른 답을 주면 이 시스템은 의미가 없다.
     *
     * <p>대신 기존 기록을 {@link #superseded}로 무효화하고, 닫힌 구간을 새로 기록한다.
     */
    public Coverage closedAt(LocalDate at, Instant recordedAt) {
        if (!at.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                    "구간 종료일은 시작일보다 뒤여야 합니다: %s → %s".formatted(validFrom, at));
        }
        return new Coverage(coverageCode, name, benefitCategory, treatmentTypes, insuredAmount,
                terms, waitingPeriodEnd, validFrom, at, recordedAt, null, false,
                ChangeType.ENDORSEMENT);
    }

    /**
     * 이 기록을 {@code at} 시점부로 무효화한 새 인스턴스를 만든다.
     *
     * @param byCorrection 정정으로 무효화하면 {@code true}(과거 사실이 틀렸었다),
     *                     변경이 구간을 닫으며 무효화하면 {@code false}(사실은 그대로다).
     *                     스냅샷 버전은 정정만 센다.
     */
    public Coverage superseded(Instant at, boolean byCorrection) {
        if (supersededAt != null) {
            throw new IllegalStateException("이미 대체된 기록입니다: " + coverageCode);
        }
        return new Coverage(coverageCode, name, benefitCategory, treatmentTypes, insuredAmount,
                terms, waitingPeriodEnd, validFrom, validTo, recordedAt, at, byCorrection,
                changeType);
    }

    /** 변경으로 이어지는 후속 구간. */
    public Coverage withTerms(CoverageTerms newTerms, Money newInsuredAmount,
                              LocalDate from, LocalDate to, Instant recordedAt) {
        return new Coverage(coverageCode, name, benefitCategory, treatmentTypes, newInsuredAmount,
                newTerms, waitingPeriodEnd, from, to, recordedAt, null, false,
                ChangeType.ENDORSEMENT);
    }

    /** 부활 시 면책기간 재기산. */
    public Coverage withWaitingPeriodEnd(LocalDate newEnd, LocalDate from, LocalDate to,
                                         Instant recordedAt) {
        return new Coverage(coverageCode, name, benefitCategory, treatmentTypes, insuredAmount,
                terms, newEnd, from, to, recordedAt, null, false, ChangeType.ENDORSEMENT);
    }

    public boolean appliesTo(TreatmentType type) {
        return treatmentTypes.contains(type);
    }

    /** {@code asOf} 시점에 면책기간이 끝났는가. */
    public boolean waitingPeriodPassed(LocalDate asOf) {
        return waitingPeriodEnd == null || !asOf.isBefore(waitingPeriodEnd);
    }

    public CoverageCode coverageCode() {
        return coverageCode;
    }

    public String name() {
        return name;
    }

    public BenefitCategory benefitCategory() {
        return benefitCategory;
    }

    public List<TreatmentType> treatmentTypes() {
        return treatmentTypes.stream().sorted().toList();
    }

    public Money insuredAmount() {
        return insuredAmount;
    }

    public CoverageTerms terms() {
        return terms;
    }

    public LocalDate waitingPeriodEnd() {
        return waitingPeriodEnd;
    }

    public ChangeType changeType() {
        return changeType;
    }

    @Override
    public LocalDate validFrom() {
        return validFrom;
    }

    @Override
    public LocalDate validTo() {
        return validTo;
    }

    @Override
    public Instant recordedAt() {
        return recordedAt;
    }

    @Override
    public Instant supersededAt() {
        return supersededAt;
    }

    @Override
    public boolean supersededByCorrection() {
        return supersededByCorrection;
    }

    @Override
    public String toString() {
        return "Coverage(%s, %s~%s)".formatted(coverageCode, validFrom, validTo);
    }
}
