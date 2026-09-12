package com.insurance.policy.adapter.web.command;

import com.insurance.policy.application.policy.PolicyCommandService;
import com.insurance.policy.domain.policy.CoverageCode;
import com.insurance.policy.domain.policy.PolicyNo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 변경·정정.
 *
 * <p>두 엔드포인트를 <b>일부러 나눴다.</b> 하나로 합치고 플래그로 구분하면
 * 실수로 정정이 나가는 순간 claims가 끝난 청구를 재심사 큐에 쌓는다.
 * URL이 다르면 코드 리뷰에서도, 접근 로그에서도, 권한 설정에서도 구분된다.
 *
 * <table border="1">
 *   <caption>변경과 정정</caption>
 *   <tr><th></th><th>변경(Endorsement)</th><th>정정(Correction)</th></tr>
 *   <tr><td>의미</td><td>"오늘부터 바뀐다"</td><td>"과거가 원래 그랬다"</td></tr>
 *   <tr><td>과거 스냅샷</td><td>불변</td><td><b>바뀜</b></td></tr>
 *   <tr><td>스냅샷 버전</td><td>그대로</td><td>+1</td></tr>
 *   <tr><td>이벤트</td><td>policy.endorsed</td><td><b>policy.corrected</b></td></tr>
 *   <tr><td>claims</td><td>읽기모델만 갱신</td><td><b>재심사 큐</b></td></tr>
 *   <tr><td>권한</td><td>ROLE_UNDERWRITER</td><td><b>ROLE_UW_MANAGER</b> + 승인 기록</td></tr>
 * </table>
 *
 * @see docs/design/02-domain-model.md §2.3
 * @see docs/design/05-api.md §3
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyChangeController {

    private final PolicyCommandService commandService;
    private final Clock clock;

    public PolicyChangeController(PolicyCommandService commandService, Clock clock) {
        this.commandService = commandService;
        this.clock = clock;
    }

    /**
     * 변경 — "오늘부터 바뀐다". 과거 스냅샷은 건드리지 않는다.
     *
     * <p>{@code actorRef}는 인증 주체에서 가져온다. 본문으로 받으면 호출자가
     * 다른 사람 이름으로 변경 이력을 남길 수 있다.
     */
    @PostMapping("/{policyNo}/endorsements")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<Void> endorse(
            @PathVariable @NotBlank String policyNo,
            @RequestBody @Valid EndorseCoverageRequest request,
            Principal principal) {

        commandService.endorse(new PolicyCommandService.EndorseCoverageCommand(
                PolicyNo.of(policyNo),
                CoverageCode.of(request.coverageCode()),
                request.terms(),
                request.insuredAmount(),
                request.effectiveFrom(),
                request.reason(),
                principal.getName()));

        return ResponseEntity.accepted().build();
    }

    /**
     * ★ 정정 — "과거가 원래 그랬다". claims의 재심사를 유발한다.
     *
     * <p>{@code approvedBy}는 <b>본문이 아니라 인증 주체</b>다. 본문으로 받으면
     * 한 사람이 요청자와 승인자를 모두 적어 넣을 수 있어 4-eyes가 형식만 남는다.
     * 요청자와 같으면 도메인이 거부하고 DB 제약이 한 번 더 막는다.
     *
     * <p>{@code ROLE_UW_MANAGER}로 제한한다 — 과거 사실을 바꾸는 행위라
     * 단독 실행을 막아야 한다.
     */
    @PostMapping("/{policyNo}/corrections")
    @PreAuthorize("hasRole('UW_MANAGER')")
    public ResponseEntity<Void> correct(
            @PathVariable @NotBlank String policyNo,
            @RequestBody @Valid CorrectExclusionRequest request,
            Principal principal) {

        Instant now = Instant.now(clock);

        commandService.correctExclusion(new PolicyCommandService.CorrectExclusionCommand(
                PolicyNo.of(policyNo),
                request.targetId(),
                request.toReplacement(now),
                request.reason(),
                request.requestedBy(),
                principal.getName()));

        return ResponseEntity.accepted().build();
    }
}
