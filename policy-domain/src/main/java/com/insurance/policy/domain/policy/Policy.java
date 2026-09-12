package com.insurance.policy.domain.policy;

import com.insurance.policy.domain.policy.event.PolicyCorrected;
import com.insurance.policy.domain.policy.event.PolicyEndorsed;
import com.insurance.policy.domain.policy.event.PolicyIssued;
import com.insurance.policy.domain.policy.event.PolicyStatusChanged;
import com.insurance.policy.domain.policy.exception.SnapshotNotAvailableException;
import com.insurance.policy.domain.shared.AggregateRoot;
import com.insurance.policy.domain.shared.Temporal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 계약 — 이 컨텍스트의 중심 애그리거트.
 *
 * <p>불변 식별 정보(계약번호·상품·피보험자·보험기간)만 직접 갖고,
 * <b>변하는 것은 전부 시간축을 가진 이력 목록</b>으로 보유한다.
 * 상태·담보·부담보는 각각 {@link PolicyVersion} / {@link Coverage} / {@link Exclusion}이며
 * 모두 {@link Temporal}이다.
 *
 * <h2>세 가지 변경 연산</h2>
 * <table border="1">
 *   <caption>변경 연산의 구분</caption>
 *   <tr><th>연산</th><th>의미</th><th>과거 스냅샷</th><th>이벤트</th></tr>
 *   <tr><td>{@link #issue}</td><td>계약 성립</td><td>—</td><td>policy.issued</td></tr>
 *   <tr><td>{@link #endorseCoverage}</td><td>"오늘부터 바뀐다"</td><td><b>불변</b></td><td>policy.endorsed</td></tr>
 *   <tr><td>{@link #correctExclusion}</td><td>"과거가 원래 그랬다"</td><td><b>바뀜</b></td><td>policy.corrected</td></tr>
 * </table>
 *
 * <p>이 구분이 정확해야 시스템이 동작한다. 정정만이 claims의 재심사를 유발한다.
 *
 * @see docs/design/02-domain-model.md §2
 */
public final class Policy extends AggregateRoot {

    private final PolicyNo policyNo;
    private final ProductRef product;
    private final String holderRef;
    private final InsuredInfo insured;
    private final PolicyPeriod period;
    private final LocalDate effectiveDate;
    private final String applicationNo;
    private final String uwCaseNo;

    private final List<PolicyVersion> versions;
    private final List<Coverage> coverages;
    private final List<Exclusion> exclusions;

    private Policy(PolicyNo policyNo, ProductRef product, String holderRef, InsuredInfo insured,
                   PolicyPeriod period, LocalDate effectiveDate, String applicationNo,
                   String uwCaseNo, List<PolicyVersion> versions, List<Coverage> coverages,
                   List<Exclusion> exclusions) {
        this.policyNo = Objects.requireNonNull(policyNo, "계약번호는 필수입니다.");
        this.product = Objects.requireNonNull(product, "상품 정보는 필수입니다.");
        this.holderRef = Objects.requireNonNull(holderRef, "계약자 식별자는 필수입니다.");
        this.insured = Objects.requireNonNull(insured, "피보험자 정보는 필수입니다.");
        this.period = Objects.requireNonNull(period, "보험기간은 필수입니다.");
        this.effectiveDate = Objects.requireNonNull(effectiveDate, "책임개시일은 필수입니다.");
        if (!period.covers(effectiveDate)) {
            throw new IllegalArgumentException(
                    "책임개시일이 보험기간 밖입니다: effectiveDate=%s, period=%s~%s"
                            .formatted(effectiveDate, period.from(), period.to()));
        }
        this.applicationNo = applicationNo;
        this.uwCaseNo = uwCaseNo;
        this.versions = new ArrayList<>(versions);
        this.coverages = new ArrayList<>(coverages);
        this.exclusions = new ArrayList<>(exclusions);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 생성
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 계약 성립.
     *
     * <p>책임개시일은 <b>승낙일과 초회보험료 납입일 중 늦은 날</b>이다.
     * 둘 중 하나라도 없으면 보장이 시작되지 않는다.
     */
    public static Policy issue(PolicyNo policyNo, ProductRef product, String holderRef,
                               InsuredInfo insured, PolicyPeriod period,
                               LocalDate acceptedOn, LocalDate firstPremiumPaidOn,
                               List<Coverage> coverages, List<Exclusion> exclusions,
                               String applicationNo, String uwCaseNo, Instant now) {
        Objects.requireNonNull(acceptedOn, "승낙일은 필수입니다.");
        Objects.requireNonNull(firstPremiumPaidOn, "초회보험료 납입일은 필수입니다.");
        LocalDate effectiveDate =
                acceptedOn.isAfter(firstPremiumPaidOn) ? acceptedOn : firstPremiumPaidOn;

        if (coverages.isEmpty()) {
            throw new IllegalArgumentException("계약은 최소 1개의 담보를 가져야 합니다.");
        }
        // 이력 레코드를 만들기 전에 검사한다. 순서를 바꾸면 PolicyVersion의
        // "유효시작일 > 종료일" 오류가 먼저 터져 실제 원인이 가려진다.
        if (!period.covers(effectiveDate)) {
            throw new IllegalArgumentException(
                    "책임개시일이 보험기간 밖입니다: effectiveDate=%s, period=%s~%s"
                            .formatted(effectiveDate, period.from(), period.to()));
        }

        PolicyVersion initial = PolicyVersion.create(
                PolicyStatus.IN_FORCE, effectiveDate, period.to(), now, "SYSTEM");

        Policy policy = new Policy(policyNo, product, holderRef, insured, period, effectiveDate,
                applicationNo, uwCaseNo, List.of(initial), coverages, exclusions);

        policy.record(new PolicyIssued(policyNo, product, insured.insuredRef(), period,
                effectiveDate, coverages.stream().map(Coverage::coverageCode).toList(),
                !exclusions.isEmpty(), now));
        return policy;
    }

    /** 영속성 어댑터가 저장된 이력을 되살릴 때 쓴다. 이벤트를 발생시키지 않는다. */
    public static Policy rehydrate(PolicyNo policyNo, ProductRef product, String holderRef,
                                   InsuredInfo insured, PolicyPeriod period,
                                   LocalDate effectiveDate, String applicationNo, String uwCaseNo,
                                   List<PolicyVersion> versions, List<Coverage> coverages,
                                   List<Exclusion> exclusions) {
        return new Policy(policyNo, product, holderRef, insured, period, effectiveDate,
                applicationNo, uwCaseNo, versions, coverages, exclusions);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ★ 시점 조회 — 이 저장소의 존재 이유
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@code asOf} 시점에 유효했고 {@code knownAt} 시점에 우리가 알고 있던 계약 상태를 굳힌다.
     *
     * <p>순수 함수다. DB도, 프레임워크도 필요 없다 — 그래서 시점 재현성을
     * 단위 테스트로 못 박을 수 있다.
     *
     * @param asOf    유효시간 기준일. 보통 사고일
     * @param knownAt 기록시간 기준. 현재 시각을 주면 "지금 아는 진실"
     */
    public PolicySnapshot snapshotAsOf(LocalDate asOf, Instant knownAt) {
        Objects.requireNonNull(asOf, "기준일(asOf)은 필수입니다.");
        Objects.requireNonNull(knownAt, "기록시점(knownAt)은 필수입니다.");

        PolicyStatus status = versions.stream()
                .filter(v -> v.isEffectiveOn(asOf, knownAt))
                .max(Comparator.comparing(PolicyVersion::recordedAt))
                .map(PolicyVersion::status)
                .orElseThrow(() -> new SnapshotNotAvailableException(policyNo, asOf, knownAt));

        List<Coverage> effectiveCoverages = coverages.stream()
                .filter(c -> c.isEffectiveOn(asOf, knownAt))
                .sorted(Comparator.comparing(c -> c.coverageCode().value()))
                .toList();

        List<Exclusion> effectiveExclusions = exclusions.stream()
                .filter(e -> e.isEffectiveOn(asOf, knownAt))
                .sorted(Comparator.comparing(e -> e.exclusionId().value()))
                .toList();

        return new PolicySnapshot(policyNo, asOf, knownAt, snapshotVersionAt(knownAt),
                product, status, period, effectiveDate, insured,
                effectiveCoverages, effectiveExclusions,
                BenefitYear.of(effectiveDate, asOf.isBefore(effectiveDate) ? effectiveDate : asOf));
    }

    /**
     * 스냅샷 버전 — {@code knownAt} 시점까지 발생한 <b>정정</b>의 수 + 1.
     *
     * <p>변경(Endorsement)은 버전을 올리지 않는다. 과거 스냅샷을 바꾸지 않기 때문이다.
     * 정정만이 "같은 asOf인데 답이 달라졌다"를 뜻하므로 버전이 필요하다.
     */
    private int snapshotVersionAt(Instant knownAt) {
        long corrections = allTemporals()
                .filter(t -> t.supersededAt() != null)
                .filter(t -> !knownAt.isBefore(t.supersededAt()))
                .count();
        return (int) corrections + 1;
    }

    private java.util.stream.Stream<Temporal> allTemporals() {
        return java.util.stream.Stream.<Temporal>concat(
                java.util.stream.Stream.concat(versions.stream(), coverages.stream()),
                exclusions.stream());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 변경 (Endorsement) — "오늘부터 바뀐다". 과거 스냅샷은 변하지 않는다
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 담보 조건 변경. 기존 구간을 {@code effectiveFrom}에서 닫고 새 구간을 연다.
     *
     * <p>{@code asOf < effectiveFrom} 인 스냅샷 조회는 여전히 기존 조건을 돌려준다.
     */
    public void endorseCoverage(CoverageCode coverageCode, CoverageTerms newTerms,
                                com.insurance.policy.domain.shared.vo.Money newInsuredAmount,
                                LocalDate effectiveFrom, String reason, String actorRef,
                                Instant now) {
        Objects.requireNonNull(effectiveFrom, "변경 적용일은 필수입니다.");
        if (effectiveFrom.isBefore(effectiveDate)) {
            throw new IllegalArgumentException(
                    "변경 적용일이 책임개시일보다 앞섭니다. 과거를 바꾸려면 정정(correction)을 쓰세요: "
                            + effectiveFrom);
        }

        Coverage current = coverages.stream()
                .filter(c -> c.coverageCode().equals(coverageCode))
                .filter(c -> c.isEffectiveOn(effectiveFrom, now))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "변경 적용일 시점에 유효한 담보가 없습니다: " + coverageCode));

        LocalDate originalEnd = current.validTo();
        coverages.remove(current);
        coverages.add(current.endingAt(effectiveFrom));
        coverages.add(current.withTerms(newTerms, newInsuredAmount, effectiveFrom, originalEnd, now));

        record(new PolicyEndorsed(policyNo, List.of(coverageCode), effectiveFrom,
                reason, actorRef, now));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 정정 (Correction) — "과거가 원래 그랬다". claims의 재심사를 유발한다
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 부담보 소급 정정.
     *
     * <p>기존 기록을 {@code supersededAt}으로 무효화한다. <b>삭제하지 않는다</b> —
     * 정정 전 시점({@code knownAt})으로 조회하면 여전히 그 부담보가 보여야
     * claims의 과거 부지급 판단을 설명할 수 있다.
     *
     * <p>{@code replacement}가 null이면 "그 부담보는 애초에 없었다"는 정정이다.
     *
     * @param approvedBy 승인자. 과거 사실을 바꾸는 행위이므로 단독 실행을 막는다
     */
    public void correctExclusion(ExclusionId exclusionId, Exclusion replacement,
                                 String reason, String requestedBy, String approvedBy,
                                 Instant now) {
        Objects.requireNonNull(reason, "정정 사유는 필수입니다.");
        Objects.requireNonNull(requestedBy, "정정 요청자는 필수입니다.");
        Objects.requireNonNull(approvedBy, "정정 승인자는 필수입니다.");
        if (approvedBy.equals(requestedBy)) {
            throw new IllegalArgumentException(
                    "정정은 요청자와 승인자가 달라야 합니다. 과거 사실을 바꾸는 행위입니다.");
        }

        Exclusion target = exclusions.stream()
                .filter(e -> e.exclusionId().equals(exclusionId))
                .filter(Temporal::isCurrentRecord)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "정정 대상 부담보를 찾을 수 없습니다: " + exclusionId));

        int previousVersion = snapshotVersionAt(now);

        exclusions.remove(target);
        exclusions.add(target.superseded(now));
        if (replacement != null) {
            exclusions.add(replacement);
        }

        record(new PolicyCorrected(policyNo,
                target.validFrom(), target.validTo(),
                List.of("EXCLUSION"),
                previousVersion, previousVersion + 1,
                reason, requestedBy, approvedBy, now));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 상태 전이
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 계약 상태를 바꾼다. 전이표에 없는 전이는 거부된다(→ HTTP 409).
     *
     * @param effectiveFrom 새 상태가 유효해지는 날
     */
    public void changeStatus(PolicyStatus to, LocalDate effectiveFrom, String reason,
                             String actorRef, Instant now) {
        PolicyVersion current = currentVersion(now);
        PolicyTransitions.ensureAllowed(policyNo, current.status(), to);

        if (!effectiveFrom.isAfter(current.validFrom())) {
            throw new IllegalArgumentException(
                    "상태 변경일은 현재 상태의 시작일보다 뒤여야 합니다: %s → %s"
                            .formatted(current.validFrom(), effectiveFrom));
        }

        LocalDate originalEnd = current.validTo();
        versions.remove(current);
        versions.add(current.endingAt(effectiveFrom));
        versions.add(PolicyVersion.endorsement(to, effectiveFrom, originalEnd, now,
                reason, actorRef));

        record(new PolicyStatusChanged(policyNo, current.status(), to, effectiveFrom,
                reason, actorRef, now));
    }

    private PolicyVersion currentVersion(Instant knownAt) {
        return versions.stream()
                .filter(Temporal::isCurrentRecord)
                .filter(v -> !knownAt.isBefore(v.recordedAt()))
                .max(Comparator.comparing(PolicyVersion::validFrom))
                .orElseThrow(() -> new IllegalStateException(
                        "계약 상태 이력이 없습니다: " + policyNo));
    }

    /** 오늘 기준 계약 상태. */
    public PolicyStatus currentStatus(LocalDate today, Instant now) {
        return versions.stream()
                .filter(v -> v.isEffectiveOn(today, now))
                .max(Comparator.comparing(PolicyVersion::recordedAt))
                .map(PolicyVersion::status)
                .orElseGet(() -> currentVersion(now).status());
    }

    // ─────────────────────────────────────────────────────────────────────

    public PolicyNo policyNo() {
        return policyNo;
    }

    public ProductRef product() {
        return product;
    }

    public String holderRef() {
        return holderRef;
    }

    public InsuredInfo insured() {
        return insured;
    }

    public PolicyPeriod period() {
        return period;
    }

    public LocalDate effectiveDate() {
        return effectiveDate;
    }

    public String applicationNo() {
        return applicationNo;
    }

    public String uwCaseNo() {
        return uwCaseNo;
    }

    /** 전체 이력. 영속성 어댑터가 저장할 때 쓴다. */
    public List<PolicyVersion> versions() {
        return List.copyOf(versions);
    }

    public List<Coverage> allCoverages() {
        return List.copyOf(coverages);
    }

    public List<Exclusion> allExclusions() {
        return List.copyOf(exclusions);
    }

    @Override
    public String toString() {
        return "Policy(%s, %s)".formatted(policyNo, product.productCode());
    }
}
