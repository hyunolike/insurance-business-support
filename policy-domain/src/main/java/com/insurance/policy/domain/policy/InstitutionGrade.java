package com.insurance.policy.domain.policy;

/**
 * 요양기관 종별.
 *
 * <p>통원 최소공제금액이 종별로 다르기 때문에 담보 조건에 등급별 금액표가 실린다.
 */
public enum InstitutionGrade {
    CLINIC("의원"),
    HOSPITAL("병원"),
    GENERAL("종합병원"),
    TERTIARY("상급종합병원"),
    PHARMACY("약국");

    private final String label;

    InstitutionGrade(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
