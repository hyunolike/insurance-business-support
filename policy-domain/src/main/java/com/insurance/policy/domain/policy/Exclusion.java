package com.insurance.policy.domain.policy;

import com.insurance.policy.domain.shared.Temporal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 부담보 — 시간축을 가진 사실.
 *
 * <p>언더라이팅 결정이 이 형태로 계약에 새겨지고, 몇 년 뒤 claims의 부지급 근거가 된다.
 *
 * <pre>
 * [청약] 고지 "5년 내 수술" → [인수] EXCLUDED 척추 M40-M54 5년
 *   → [스냅샷] exclusions[] → [청구] 주상병 M51.2 → [판정] D-POL-004 부지급
 *   → 역추적: uwCaseNo → UwRuleTrace → "5년 내 입원 이력으로 부과"
 * </pre>
 *
 * <p>{@code uwCaseNo}가 그 역추적 경로다. "왜 내 척추는 보장이 안 되나"에
 * 뿌리까지 답할 수 있게 하는 유일한 연결 고리이므로 반드시 남긴다.
 */
public final class Exclusion implements Temporal {

    private final ExclusionId exclusionId;
    private final ExclusionType type;
    private final String target;
    private final List<KcdRange> kcdRanges;
    private final String reason;
    private final String uwCaseNo;

    private final LocalDate validFrom;
    private final LocalDate validTo;
    private final Instant recordedAt;
    private final Instant supersededAt;
    private final boolean supersededByCorrection;
    private final ChangeType changeType;

    private Exclusion(ExclusionId exclusionId, ExclusionType type, String target,
                      List<KcdRange> kcdRanges, String reason, String uwCaseNo,
                      LocalDate validFrom, LocalDate validTo, Instant recordedAt,
                      Instant supersededAt, boolean supersededByCorrection,
                      ChangeType changeType) {
        this.exclusionId = Objects.requireNonNull(exclusionId, "부담보 식별자는 필수입니다.");
        this.type = Objects.requireNonNull(type, "부담보 유형은 필수입니다.");
        this.target = Objects.requireNonNull(target, "부담보 대상은 필수입니다.");
        this.kcdRanges = List.copyOf(Objects.requireNonNull(kcdRanges, "KCD 범위는 필수입니다."));
        if (this.kcdRanges.isEmpty()) {
            throw new IllegalArgumentException(
                    "부담보는 최소 1개의 KCD 범위를 가져야 합니다. "
                            + "범위 없는 부담보는 claims가 판정할 수 없습니다.");
        }
        this.reason = Objects.requireNonNull(reason, "부담보 사유는 필수입니다.");
        this.uwCaseNo = uwCaseNo;
        this.validFrom = Objects.requireNonNull(validFrom, "유효시작일은 필수입니다.");
        this.validTo = Objects.requireNonNull(validTo, "유효종료일은 필수입니다.");
        if (!validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException(
                    "부담보 시작일은 종료일보다 앞서야 합니다: %s ~ %s".formatted(validFrom, validTo));
        }
        this.recordedAt = Objects.requireNonNull(recordedAt, "기록시각은 필수입니다.");
        this.supersededAt = supersededAt;
        this.supersededByCorrection = supersededByCorrection;
        this.changeType = Objects.requireNonNull(changeType, "변경유형은 필수입니다.");
    }

    public static Exclusion create(ExclusionId exclusionId, ExclusionType type, String target,
                                   List<KcdRange> kcdRanges, String reason, String uwCaseNo,
                                   LocalDate validFrom, LocalDate validTo, Instant recordedAt) {
        return new Exclusion(exclusionId, type, target, kcdRanges, reason, uwCaseNo,
                validFrom, validTo, recordedAt, null, false, ChangeType.CREATE);
    }

    /**
     * 이 기록을 {@code at} 시점부로 무효화한 새 인스턴스를 만든다.
     *
     * @param byCorrection 정정이면 {@code true}. 스냅샷 버전은 정정만 센다.
     */
    public Exclusion superseded(Instant at, boolean byCorrection) {
        if (supersededAt != null) {
            throw new IllegalStateException("이미 대체된 부담보입니다: " + exclusionId);
        }
        return new Exclusion(exclusionId, type, target, kcdRanges, reason, uwCaseNo,
                validFrom, validTo, recordedAt, at, byCorrection, changeType);
    }

    /**
     * 주어진 질병분류코드가 이 부담보에 저촉되는가.
     *
     * <p>claims의 {@code R-POL-050}이 같은 판정을 한다. 여기서는 판정 근거를 제공할 뿐
     * 부지급을 결정하지 않는다 — 그것은 보상 업무의 책임이다.
     */
    public boolean covers(String kcdCode) {
        return kcdRanges.stream().anyMatch(range -> range.contains(kcdCode));
    }

    public ExclusionId exclusionId() {
        return exclusionId;
    }

    public ExclusionType type() {
        return type;
    }

    public String target() {
        return target;
    }

    public List<KcdRange> kcdRanges() {
        return kcdRanges;
    }

    public String reason() {
        return reason;
    }

    /** 이 부담보를 부과한 인수심사 건번호. 역추적 경로. */
    public String uwCaseNo() {
        return uwCaseNo;
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
        return "Exclusion(%s, %s, %s~%s)".formatted(exclusionId, target, validFrom, validTo);
    }
}
