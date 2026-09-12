package com.insurance.policy.adapter.web.snapshot;

import com.insurance.policy.domain.policy.InsuredRef;
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
 * ★ 계약 스냅샷 API — 이 저장소에서 가장 중요한 엔드포인트.
 *
 * <p>claims의 심사 근거가 여기서 나온다. 응답이 한 번 잘못되면 그 청구의 판단이
 * 영구히 잘못되므로, 이 API는 <b>공표된 공용 계약</b>으로 다룬다.
 *
 * <p>고객 토큰으로는 접근할 수 없다. 서비스 간 호출 전용이며 스코프 기반 인가를 쓴다 —
 * claims 외의 소비자가 붙을 수 있기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicySnapshotController {

    private final CachedSnapshotRenderer renderer;

    public PolicySnapshotController(CachedSnapshotRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 사고일 시점의 계약 스냅샷.
     *
     * @param asOf      유효시간 기준일 = 사고일
     * @param insuredRef 피보험자 식별자. 계약과 불일치하면 404 (403이 아니다 —
     *                   403은 그 계약이 존재한다는 사실을 노출한다)
     * @param knownAt   기록시간 기준. 생략하면 현재 = "지금 아는 진실".
     *                  정정 전 판단을 재현하려면 당시 시각을 넘긴다
     */
    @GetMapping("/{policyNo}/snapshot")
    @PreAuthorize("hasAuthority('SCOPE_policy.snapshot.read')")
    public ResponseEntity<PolicySnapshotResponse> snapshot(
            @PathVariable @NotBlank String policyNo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam @NotBlank String insuredRef,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant knownAt) {

        // 렌더·체크섬·캐시는 CachedSnapshotRenderer 가 맡는다.
        // knownAt 이 명시된 과거 조회만 캐시된다 — 그 답은 영원히 변하지 않기 때문이다.
        return ResponseEntity.ok(renderer.render(
                PolicyNo.of(policyNo), InsuredRef.of(insuredRef), asOf, knownAt));
    }
}
