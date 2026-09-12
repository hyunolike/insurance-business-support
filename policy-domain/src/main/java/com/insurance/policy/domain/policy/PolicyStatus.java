package com.insurance.policy.domain.policy;

/**
 * 계약 상태.
 *
 * <p>{@code coversAccident} 는 <b>이 컨텍스트의 판단이 아니라 claims에 전달되는 사실</b>이다.
 * 최종 보상 판정은 claims의 R-POL-* 룰이 한다. 여기서는 상태의 성격만 표현한다.
 */
public enum PolicyStatus {

    /** 청약접수 — 계약 미성립 */
    APPLIED("청약접수", false),
    /** 심사중 — 계약 미성립 */
    UNDERWRITING("심사중", false),
    /** 인수거절 */
    DECLINED("인수거절", false),
    /** 정상 */
    IN_FORCE("정상", true),
    /**
     * 납입최고(유예기간).
     * 보상은 가능하나 미납보험료 상계 판단이 필요하므로 claims에서 회부된다(R-POL-031).
     */
    GRACE("납입최고", true),
    /** 실효 — 보험료 미납으로 효력 상실 */
    LAPSED("실효", false),
    /** 부활 — 재고지·재심사를 거쳐 효력 회복. 면책기간이 재기산된다 */
    REINSTATED("부활", true),
    /** 해지 */
    SURRENDERED("해지", false),
    /** 만기 */
    MATURED("만기", false),
    /** 취소·철회 — 소급 무효 */
    CANCELLED("취소", false);

    private final String label;
    private final boolean coversAccident;

    PolicyStatus(String label, boolean coversAccident) {
        this.label = label;
        this.coversAccident = coversAccident;
    }

    public String label() {
        return label;
    }

    /** 이 상태에서 발생한 사고가 보상 대상이 될 수 있는가. */
    public boolean coversAccident() {
        return coversAccident;
    }

    public boolean isTerminal() {
        return this == DECLINED || this == SURRENDERED || this == MATURED || this == CANCELLED;
    }
}
