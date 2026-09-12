package com.insurance.policy.domain.policy;

/** 부담보 유형. */
public enum ExclusionType {
    /** 특정 부위 부담보 — 예: 척추 및 그 부속기관 */
    BODY_PART("특정부위 부담보"),
    /** 특정 질병 부담보 */
    DISEASE("특정질병 부담보"),
    /** KCD 코드 범위로 직접 지정 */
    KCD_RANGE("질병분류 범위 부담보");

    private final String label;

    ExclusionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
