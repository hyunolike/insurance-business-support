package com.insurance.policy.domain.policy;

/**
 * 보장 구분. claims의 금액 산출에서 기준액을 고르는 키가 된다.
 *
 * <p>급여와 비급여는 자기부담금을 <b>각각 따로</b> 계산한 뒤 합산한다.
 * 이 순서를 섞으면 금액이 틀어진다.
 */
public enum BenefitCategory {
    /** 급여 — 국민건강보험 적용. 본인부담금만 보상 대상이고 공단부담금은 아니다 */
    COVERED("급여"),
    /** 비급여 — 건강보험 미적용. 3대 비급여를 제외한 부분 */
    UNCOVERED("비급여"),
    /** 3대 비급여 — 도수·체외충격파·증식치료 / 비급여주사 / MRI·MRA. 별도 특약·별도 한도 */
    MAJOR_UNCOVERED("3대 비급여");

    private final String label;

    BenefitCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
