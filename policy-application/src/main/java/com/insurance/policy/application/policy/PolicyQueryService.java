package com.insurance.policy.application.policy;

import com.insurance.policy.application.port.out.PolicyRepository;
import com.insurance.policy.domain.policy.Policy;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.exception.PolicyNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 조회 — <b>사내 운영용</b>.
 *
 * <p>{@link PolicySnapshotService}와 목적이 다르다.
 *
 * <table border="1">
 *   <caption>두 조회 경로의 차이</caption>
 *   <tr><th></th><th>스냅샷 조회</th><th>이 서비스</th></tr>
 *   <tr><td>소비자</td><td>claims (외부 시스템)</td><td>사내 운영자</td></tr>
 *   <tr><td>계약</td><td>공표된 공용 계약. 깨면 CI 실패</td><td>내부용. 자유롭게 바뀐다</td></tr>
 *   <tr><td>필드</td><td>심사에 필요한 최소</td><td>운영에 필요한 것(청약번호·인수심사번호 등)</td></tr>
 *   <tr><td>대체된 기록</td><td>보이지 않는다</td><td><b>보인다</b> — 이력 조회가 목적</td></tr>
 * </table>
 *
 * <p>둘을 한 엔드포인트로 합치지 않는 이유: 합치면 운영 편의로 필드를 하나 더 넣을 때마다
 * claims의 공용 계약이 흔들리고, 최소권한 경계도 함께 무너진다.
 */
@Service
public class PolicyQueryService {

    /** 이력 조회에서 걸러낼 수 있는 요소. 오타를 400으로 돌려주기 위해 목록을 고정한다. */
    private static final Set<String> HISTORY_ELEMENTS =
            Set.of("COVERAGE", "EXCLUSION", "STATUS");

    private final PolicyRepository policyRepository;
    private final Clock clock;

    public PolicyQueryService(PolicyRepository policyRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.clock = clock;
    }

    /**
     * 특정 시점의 계약 상세.
     *
     * <p>기본값은 "오늘, 지금 아는 진실"이다. 두 값을 넘기면 과거 어느 시점의 판단도
     * 그대로 재현된다 — 민원 응대에서 "그때 우리가 뭘 보고 있었나"를 확인하는 경로다.
     *
     * @param asOf    {@code null}이면 오늘
     * @param knownAt {@code null}이면 현재
     */
    @Transactional(readOnly = true)
    public PolicyDetail detail(PolicyNo policyNo, LocalDate asOf, Instant knownAt) {
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now(clock) : asOf;
        Instant effectiveKnownAt = knownAt == null ? Instant.now(clock) : knownAt;

        // 전체 이력을 읽는다. 상세 화면은 뜨거운 경로가 아니고,
        // 스냅샷 버전이 정정 횟수로 결정되므로 대체된 기록까지 필요하다.
        Policy policy = policyRepository.load(policyNo)
                .orElseThrow(() -> new PolicyNotFoundException(policyNo));

        return new PolicyDetail(policy, effectiveAsOf, effectiveKnownAt,
                policy.snapshotAsOf(effectiveAsOf, effectiveKnownAt));
    }

    /**
     * 변경 이력.
     *
     * <p>정정으로 무효화된 기록도 포함한다. "언제 무엇이 어떻게 바뀌었나"가 목적인데
     * 무효화된 기록을 숨기면 정정 사실 자체가 이력에서 사라진다.
     *
     * @param element {@code null}이면 전체
     */
    @Transactional(readOnly = true)
    public List<PolicyRepository.ChangeHistoryEntry> history(PolicyNo policyNo, String element) {
        if (element != null && !HISTORY_ELEMENTS.contains(element)) {
            throw new IllegalArgumentException(
                    "알 수 없는 이력 구분입니다: %s (가능: %s)".formatted(element, HISTORY_ELEMENTS));
        }
        if (!policyRepository.exists(policyNo)) {
            throw new PolicyNotFoundException(policyNo);
        }
        return policyRepository.findChangeHistory(policyNo, element);
    }

    /** 계약 상세. 애그리거트와 그 시점의 스냅샷을 함께 넘긴다. */
    public record PolicyDetail(Policy policy, LocalDate asOf, Instant knownAt,
                               com.insurance.policy.domain.policy.PolicySnapshot snapshot) {
    }
}
