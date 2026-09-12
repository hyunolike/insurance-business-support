package com.insurance.policy.adapter.web.query;

import com.insurance.policy.application.policy.PolicyQueryService;
import com.insurance.policy.domain.policy.PolicyNo;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 조회 — 사내 운영용.
 *
 * <p>스냅샷 API({@code /snapshot})와 <b>다른 권한</b>을 쓴다.
 * 스냅샷은 claims 같은 서비스 토큰용이고, 이쪽은 운영자용이다.
 * 같은 스코프를 공유하면 한쪽에 권한을 주는 순간 다른 쪽도 열린다.
 *
 * @see docs/design/05-api.md §2
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyQueryController {

    private final PolicyQueryService queryService;

    public PolicyQueryController(PolicyQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 계약 상세.
     *
     * <p>{@code asOf}/{@code knownAt}을 넘기면 과거 시점을 그대로 재현한다.
     * 민원 응대에서 "그때 우리가 뭘 보고 있었나"를 확인하는 경로다.
     * 생략하면 "오늘, 지금 아는 진실".
     */
    @GetMapping("/{policyNo}")
    @PreAuthorize("hasAuthority('SCOPE_policy.read')")
    public ResponseEntity<PolicyDetailResponse> detail(
            @PathVariable @NotBlank String policyNo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant knownAt) {

        var detail = queryService.detail(PolicyNo.of(policyNo), asOf, knownAt);
        return ResponseEntity.ok(PolicyDetailResponse.from(detail));
    }

    /**
     * 변경 이력.
     *
     * <p>정정으로 무효화된 기록도 함께 나온다 — 숨기면 정정 사실 자체가 이력에서 사라진다.
     *
     * @param element {@code COVERAGE} / {@code EXCLUSION} / {@code STATUS}. 생략하면 전체
     */
    @GetMapping("/{policyNo}/history")
    @PreAuthorize("hasAuthority('SCOPE_policy.read')")
    public ResponseEntity<ChangeHistoryResponse> history(
            @PathVariable @NotBlank String policyNo,
            @RequestParam(required = false) String element) {

        var entries = queryService.history(PolicyNo.of(policyNo), element);
        return ResponseEntity.ok(ChangeHistoryResponse.of(policyNo, entries));
    }
}
