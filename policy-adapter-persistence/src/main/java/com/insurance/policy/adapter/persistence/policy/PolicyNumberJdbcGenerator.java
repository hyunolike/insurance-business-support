package com.insurance.policy.adapter.persistence.policy;

import com.insurance.policy.application.port.out.PolicyNumberGenerator;
import com.insurance.policy.domain.policy.ExclusionId;
import com.insurance.policy.domain.policy.PolicyNo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DB 시퀀스 기반 식별자 발급.
 *
 * <p>시퀀스는 트랜잭션 롤백 시에도 되돌아가지 않는다 — 번호에 구멍이 생긴다.
 * 그것이 충돌 없음·발급 순서 보존을 위해 치르는 대가이며, 의도된 동작이다.
 */
@Component
public class PolicyNumberJdbcGenerator implements PolicyNumberGenerator {

    private final JdbcTemplate jdbc;

    public PolicyNumberJdbcGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PolicyNo nextPolicyNo(int year) {
        Long seq = jdbc.queryForObject("SELECT nextval('policy_no_seq')", Long.class);
        return PolicyNo.of(year, seq == null ? 1L : seq);
    }

    @Override
    public ExclusionId nextExclusionId(int year) {
        Long seq = jdbc.queryForObject("SELECT nextval('exclusion_id_seq')", Long.class);
        return ExclusionId.of(year, seq == null ? 1L : seq);
    }
}
