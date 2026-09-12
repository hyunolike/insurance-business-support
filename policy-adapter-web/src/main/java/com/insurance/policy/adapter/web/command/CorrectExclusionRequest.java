package com.insurance.policy.adapter.web.command;

import com.insurance.policy.domain.policy.Exclusion;
import com.insurance.policy.domain.policy.ExclusionId;
import com.insurance.policy.domain.policy.ExclusionType;
import com.insurance.policy.domain.policy.KcdRange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * ★ 소급 정정 요청 — "과거가 원래 그랬다".
 *
 * <p>변경과 근본적으로 다르다. 과거 스냅샷이 바뀌고, {@code policy.corrected}가 나가고,
 * claims가 이미 끝난 청구를 재심사 큐에 올린다. 그래서 절차가 무겁다.
 *
 * <h2>누가 무엇을 채우는가</h2>
 * <ul>
 *   <li>{@code requestedBy} — <b>본문.</b> 정정을 요청한 사람</li>
 *   <li>{@code approvedBy} — <b>본문에 없다.</b> 인증 주체에서 가져온다.
 *       승인자를 본문으로 받으면 혼자서 요청자와 승인자를 모두 적어 넣을 수 있고,
 *       그러면 4-eyes 원칙이 형식만 남는다. 호출한 사람이 곧 승인자다</li>
 * </ul>
 *
 * <p>두 값이 같으면 도메인이 거부하고 DB 제약이 한 번 더 막는다.
 *
 * <p>{@code replacement}가 {@code null}이면 <b>부담보 삭제</b> 정정이다 —
 * "애초에 이 부담보는 없었어야 했다". 값이 있으면 교체다.
 */
public record CorrectExclusionRequest(
        @NotBlank(message = "정정 대상 부담보 식별자는 필수입니다.")
        String exclusionId,

        @Valid
        Replacement replacement,

        @NotBlank(message = "정정 사유는 필수입니다. 과거를 바꾸는 행위이므로 기록이 남아야 합니다.")
        String reason,

        @NotBlank(message = "정정 요청자는 필수입니다.")
        String requestedBy
) {

    public ExclusionId targetId() {
        return ExclusionId.of(exclusionId);
    }

    /** {@code null}이면 삭제 정정이다. */
    public Exclusion toReplacement(Instant recordedAt) {
        return replacement == null ? null : replacement.toDomain(exclusionId, recordedAt);
    }

    /**
     * 교체할 부담보 조건.
     *
     * <p>식별자는 받지 않는다 — 정정 대상과 같은 {@code exclusionId}를 쓴다.
     * 다른 식별자를 허용하면 "정정"이 아니라 삭제 후 신규 등록이 되고,
     * 이력에서 두 사실의 연결이 끊긴다.
     */
    public record Replacement(
            @NotBlank(message = "부담보 유형은 필수입니다.")
            String type,

            @NotBlank(message = "부담보 대상은 필수입니다.")
            String target,

            @NotEmpty(message = "KCD 범위는 최소 1개가 필요합니다. "
                    + "범위 없는 부담보는 claims가 판정할 수 없습니다.")
            List<String> kcdRanges,

            @NotNull(message = "부담보 시작일은 필수입니다.")
            LocalDate validFrom,

            @NotNull(message = "부담보 종료일은 필수입니다.")
            LocalDate validTo,

            @NotBlank(message = "부담보 사유는 필수입니다.")
            String reason,

            String uwCaseNo
    ) {

        Exclusion toDomain(String exclusionId, Instant recordedAt) {
            return Exclusion.create(
                    ExclusionId.of(exclusionId),
                    parseType(type),
                    target,
                    kcdRanges.stream().map(KcdRange::parse).toList(),
                    reason,
                    uwCaseNo,
                    validFrom,
                    validTo,
                    recordedAt);
        }

        /** 알 수 없는 유형은 400으로 돌려준다 — enum 파싱 실패를 500으로 내보내지 않는다. */
        private static ExclusionType parseType(String value) {
            try {
                return ExclusionType.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "알 수 없는 부담보 유형입니다: %s (가능: %s)"
                                .formatted(value,
                                        java.util.Arrays.toString(ExclusionType.values())));
            }
        }
    }
}
