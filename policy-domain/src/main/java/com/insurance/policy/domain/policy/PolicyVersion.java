package com.insurance.policy.domain.policy;

import com.insurance.policy.domain.shared.Temporal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** 계약 상태 이력 — 시간축을 가진 사실. */
public final class PolicyVersion implements Temporal {

    private final PolicyStatus status;
    private final LocalDate validFrom;
    private final LocalDate validTo;
    private final Instant recordedAt;
    private final Instant supersededAt;
    private final ChangeType changeType;
    private final String reason;
    private final String actorRef;

    private PolicyVersion(PolicyStatus status, LocalDate validFrom, LocalDate validTo,
                          Instant recordedAt, Instant supersededAt, ChangeType changeType,
                          String reason, String actorRef) {
        this.status = Objects.requireNonNull(status, "계약상태는 필수입니다.");
        this.validFrom = Objects.requireNonNull(validFrom, "유효시작일은 필수입니다.");
        this.validTo = Objects.requireNonNull(validTo, "유효종료일은 필수입니다.");
        if (!validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException(
                    "유효시작일은 종료일보다 앞서야 합니다: %s ~ %s".formatted(validFrom, validTo));
        }
        this.recordedAt = Objects.requireNonNull(recordedAt, "기록시각은 필수입니다.");
        this.supersededAt = supersededAt;
        this.changeType = Objects.requireNonNull(changeType, "변경유형은 필수입니다.");
        this.reason = reason;
        this.actorRef = Objects.requireNonNull(actorRef, "행위자는 필수입니다.");
    }

    public static PolicyVersion create(PolicyStatus status, LocalDate validFrom, LocalDate validTo,
                                       Instant recordedAt, String actorRef) {
        return new PolicyVersion(status, validFrom, validTo, recordedAt, null,
                ChangeType.CREATE, null, actorRef);
    }

    public static PolicyVersion endorsement(PolicyStatus status, LocalDate validFrom,
                                            LocalDate validTo, Instant recordedAt,
                                            String reason, String actorRef) {
        return new PolicyVersion(status, validFrom, validTo, recordedAt, null,
                ChangeType.ENDORSEMENT, reason, actorRef);
    }

    /** 변경: 이 구간을 {@code at}에서 닫는다. */
    public PolicyVersion endingAt(LocalDate at) {
        if (!at.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                    "구간 종료일은 시작일보다 뒤여야 합니다: %s → %s".formatted(validFrom, at));
        }
        return new PolicyVersion(status, validFrom, at, recordedAt, supersededAt,
                changeType, reason, actorRef);
    }

    /** 정정: 이 기록을 무효화한다. */
    public PolicyVersion superseded(Instant at) {
        if (supersededAt != null) {
            throw new IllegalStateException("이미 정정된 기록입니다.");
        }
        return new PolicyVersion(status, validFrom, validTo, recordedAt, at,
                changeType, reason, actorRef);
    }

    public PolicyStatus status() {
        return status;
    }

    public ChangeType changeType() {
        return changeType;
    }

    public String reason() {
        return reason;
    }

    public String actorRef() {
        return actorRef;
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
    public String toString() {
        return "PolicyVersion(%s, %s~%s)".formatted(status, validFrom, validTo);
    }
}
