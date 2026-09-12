package com.insurance.policy.domain.policy;

import com.insurance.policy.domain.policy.exception.IllegalPolicyTransitionException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 계약 상태 전이표. <b>전이 규칙의 단일 진실 공급원.</b>
 *
 * <p>이 표에 없는 전이는 발생할 수 없다. 위반은 {@link IllegalPolicyTransitionException}이며
 * API 계층에서 <b>409 Conflict</b>로 매핑된다 — 500이 아니다.
 *
 * @see docs/design/00-domain-glossary.md §2.2
 */
public final class PolicyTransitions {

    private static final Map<PolicyStatus, Set<PolicyStatus>> ALLOWED =
            new EnumMap<>(PolicyStatus.class);

    static {
        ALLOWED.put(PolicyStatus.APPLIED, Set.of(PolicyStatus.UNDERWRITING, PolicyStatus.CANCELLED));
        ALLOWED.put(PolicyStatus.UNDERWRITING, Set.of(PolicyStatus.IN_FORCE, PolicyStatus.DECLINED));
        ALLOWED.put(PolicyStatus.IN_FORCE, Set.of(
                PolicyStatus.GRACE, PolicyStatus.SURRENDERED,
                PolicyStatus.MATURED, PolicyStatus.CANCELLED));
        ALLOWED.put(PolicyStatus.GRACE, Set.of(PolicyStatus.IN_FORCE, PolicyStatus.LAPSED));
        ALLOWED.put(PolicyStatus.LAPSED, Set.of(PolicyStatus.REINSTATED, PolicyStatus.SURRENDERED));
        ALLOWED.put(PolicyStatus.REINSTATED, Set.of(PolicyStatus.IN_FORCE));
        ALLOWED.put(PolicyStatus.DECLINED, Set.of());
        ALLOWED.put(PolicyStatus.SURRENDERED, Set.of());
        ALLOWED.put(PolicyStatus.MATURED, Set.of());
        ALLOWED.put(PolicyStatus.CANCELLED, Set.of());
    }

    private PolicyTransitions() {
    }

    public static boolean isAllowed(PolicyStatus from, PolicyStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void ensureAllowed(PolicyNo policyNo, PolicyStatus from, PolicyStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalPolicyTransitionException(policyNo, from, to);
        }
    }

    public static Set<PolicyStatus> allowedFrom(PolicyStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
