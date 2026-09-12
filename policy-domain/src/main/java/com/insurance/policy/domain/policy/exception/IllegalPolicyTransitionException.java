package com.insurance.policy.domain.policy.exception;

import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.policy.PolicyTransitions;

/**
 * 허용되지 않은 계약 상태 전이.
 *
 * <p>API 계층에서 <b>409 Conflict</b>로 매핑된다. 500이 아니다 —
 * 도메인 규칙 위반은 클라이언트가 이해할 수 있는 오류다.
 */
public class IllegalPolicyTransitionException extends RuntimeException {

    private final transient PolicyNo policyNo;
    private final PolicyStatus from;
    private final PolicyStatus to;

    public IllegalPolicyTransitionException(PolicyNo policyNo, PolicyStatus from, PolicyStatus to) {
        super("현재 상태(%s)에서 %s(으)로 전이할 수 없습니다. 허용되는 전이: %s"
                .formatted(from.label(), to.label(), PolicyTransitions.allowedFrom(from)));
        this.policyNo = policyNo;
        this.from = from;
        this.to = to;
    }

    public PolicyNo policyNo() {
        return policyNo;
    }

    public PolicyStatus from() {
        return from;
    }

    public PolicyStatus to() {
        return to;
    }
}
