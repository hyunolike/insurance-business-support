package com.insurance.policy.application.port.out;

import com.insurance.policy.domain.policy.ExclusionId;
import com.insurance.policy.domain.policy.PolicyNo;

/**
 * 계약번호·부담보 식별자 발급.
 *
 * <p>DB 시퀀스로 구현한다. 난수를 쓰지 않는 이유는 충돌 가능성, 발급 순서 상실,
 * 재시도 시 멱등성 파괴다.
 */
public interface PolicyNumberGenerator {

    PolicyNo nextPolicyNo(int year);

    ExclusionId nextExclusionId(int year);
}
