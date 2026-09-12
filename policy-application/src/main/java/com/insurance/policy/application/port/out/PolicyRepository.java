package com.insurance.policy.application.port.out;

import com.insurance.policy.domain.policy.Policy;
import com.insurance.policy.domain.policy.PolicyNo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 계약 저장소.
 *
 * <p>전체 이력을 항상 메모리에 올리는 것은 비현실적이므로 두 가지 로드 경로를 둔다.
 */
public interface PolicyRepository {

    /** 신규 계약을 저장한다. */
    void save(Policy policy);

    /**
     * 쓰기 경로용 — <b>전체 이력</b>을 로드한다.
     *
     * <p>대체된 기록까지 읽는 이유가 두 가지 있다.
     * <ul>
     *   <li>스냅샷 버전은 지금까지의 정정 횟수로 결정된다. 대체된 기록을 빼고 읽으면
     *       두 번째 정정부터 버전이 틀어진다.</li>
     *   <li>이미 저장된 행을 다시 INSERT하지 않으려면 무엇이 저장돼 있는지 알아야 한다.</li>
     * </ul>
     *
     * <p>계약 1건의 이력은 많아야 수십 행이라 전부 읽어도 부담이 없다.
     * 뜨거운 경로인 스냅샷 조회는 {@link #findAsOf}가 필요한 행만 읽는다.
     */
    Optional<Policy> load(PolicyNo policyNo);

    /**
     * 시점 조회 전용 — {@code asOf}/{@code knownAt}에 필요한 행만 로드한다.
     *
     * <p>스냅샷 API의 뜨거운 경로다. 전체 이력을 읽지 않는 것이 핵심이다.
     */
    Optional<Policy> findAsOf(PolicyNo policyNo, LocalDate asOf, Instant knownAt);

    /** 변경(Endorsement) 결과를 반영한다. 기존 구간을 닫고 새 행을 넣는다. */
    void applyEndorsement(Policy policy);

    /**
     * 정정(Correction) 결과를 반영한다.
     *
     * <p>{@code superseded_at} 마킹은 이 시스템에서 <b>유일하게 허용되는 UPDATE</b>다.
     * DB 트리거가 그 외의 수정을 거부한다.
     */
    void applyCorrection(Policy policy, CorrectionRecord record);

    /** 상태 전이 결과를 반영한다. */
    void applyStatusChange(Policy policy);

    boolean exists(PolicyNo policyNo);

    /**
     * 변경 이력 조회.
     *
     * <p>정정된 기록도 포함한다 — "언제 무엇이 어떻게 바뀌었나"를 보여주는 것이 목적이므로
     * 무효화된 기록을 숨기면 의미가 없다.
     *
     * @param element {@code null}이면 전체. {@code COVERAGE} / {@code EXCLUSION} / {@code STATUS}
     */
    java.util.List<ChangeHistoryEntry> findChangeHistory(PolicyNo policyNo, String element);

    /** 이력 1건. */
    record ChangeHistoryEntry(
            String element,
            String changeType,
            String targetRef,
            LocalDate validFrom,
            LocalDate validTo,
            Instant recordedAt,
            Instant supersededAt,
            String detail,
            String reason,
            String actorRef
    ) {
    }

    /** 정정 이력 1건. 승인자가 필수다 — 과거를 바꾸는 행위이므로. */
    record CorrectionRecord(
            PolicyNo policyNo,
            String correctionType,
            java.util.List<String> affectedElements,
            LocalDate scopeValidFrom,
            LocalDate scopeValidTo,
            int previousVersion,
            int newVersion,
            String reason,
            String requestedBy,
            String approvedBy,
            Instant correctedAt
    ) {
    }
}
