package com.insurance.policy.domain.policy.exception;

import com.insurance.policy.domain.policy.PolicyNo;

/**
 * 계약을 찾을 수 없음. API 계층에서 <b>404</b>로 매핑된다.
 *
 * <p>피보험자 식별자가 불일치하는 경우에도 이 예외를 쓴다.
 * 403을 주면 "그 계약번호는 존재한다"는 사실이 새어 나가기 때문이다.
 */
public class PolicyNotFoundException extends RuntimeException {

    private final transient PolicyNo policyNo;

    public PolicyNotFoundException(PolicyNo policyNo) {
        super("계약을 찾을 수 없습니다: " + policyNo);
        this.policyNo = policyNo;
    }

    public PolicyNo policyNo() {
        return policyNo;
    }
}
