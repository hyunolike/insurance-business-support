package com.insurance.policy.adapter.web.command;

import com.insurance.policy.domain.policy.CoverageTerms;
import com.insurance.policy.domain.policy.InstitutionGrade;
import com.insurance.policy.domain.shared.vo.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * 계약 변경 요청 — "오늘부터 바뀐다".
 *
 * <p>{@code actorRef}가 <b>본문에 없다</b>. 인증 주체에서 가져온다.
 * 본문으로 받으면 호출자가 다른 사람 이름으로 변경 이력을 남길 수 있다.
 *
 * <p>{@code effectiveFrom}은 미래여도 된다 — 예약 변경이 실무에 흔하다.
 * 다만 과거일 수는 없다. 과거부터 바꾸는 것은 변경이 아니라 <b>정정</b>이고,
 * 그쪽은 승인자가 필요한 별도 엔드포인트다.
 */
public record EndorseCoverageRequest(
        @NotBlank(message = "담보 코드는 필수입니다.")
        String coverageCode,

        @NotNull(message = "변경 후 가입금액은 필수입니다.")
        @PositiveOrZero(message = "가입금액은 음수가 될 수 없습니다.")
        Long newInsuredAmount,

        @NotNull(message = "변경 후 담보 조건은 필수입니다.")
        @Valid
        Terms newTerms,

        @NotNull(message = "변경 효력일은 필수입니다.")
        LocalDate effectiveFrom,

        @NotBlank(message = "변경 사유는 필수입니다.")
        String reason
) {

    public Money insuredAmount() {
        return Money.ofWon(newInsuredAmount);
    }

    public CoverageTerms terms() {
        return newTerms.toDomain();
    }

    /**
     * 담보 조건.
     *
     * <p>{@code coinsuranceRate}를 {@code BigDecimal}로 받는다. 부동소수로 받으면
     * 0.2가 0.20000000000000001이 되어 자기부담금 계산에 오차가 생긴다.
     */
    public record Terms(
            @NotNull(message = "자기부담률은 필수입니다.")
            @DecimalMin(value = "0.0", message = "자기부담률은 0 이상이어야 합니다.")
            @DecimalMax(value = "1.0", message = "자기부담률은 1 이하여야 합니다.")
            BigDecimal coinsuranceRate,

            @PositiveOrZero(message = "최소공제금액은 음수가 될 수 없습니다.")
            Long minDeductible,

            Map<String, Long> minDeductibleByGrade,

            @PositiveOrZero(message = "연간 한도는 음수가 될 수 없습니다.")
            Long annualLimit,

            @PositiveOrZero(message = "1회 한도는 음수가 될 수 없습니다.")
            Long perVisitLimit,

            @PositiveOrZero(message = "연간 횟수 한도는 음수가 될 수 없습니다.")
            Integer annualCountLimit
    ) {

        CoverageTerms toDomain() {
            Map<InstitutionGrade, Long> byGrade = new EnumMap<>(InstitutionGrade.class);
            if (minDeductibleByGrade != null) {
                minDeductibleByGrade.forEach((grade, amount) ->
                        byGrade.put(parseGrade(grade), amount));
            }
            return new CoverageTerms(coinsuranceRate, minDeductible, byGrade,
                    annualLimit, perVisitLimit, annualCountLimit);
        }

        /** 알 수 없는 종별은 400으로 돌려준다 — enum 파싱 실패를 500으로 내보내지 않는다. */
        private static InstitutionGrade parseGrade(String value) {
            try {
                return InstitutionGrade.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "알 수 없는 의료기관 종별입니다: %s (가능: %s)"
                                .formatted(value, java.util.Arrays.toString(
                                        InstitutionGrade.values())));
            }
        }
    }
}
