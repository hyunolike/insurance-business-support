package com.insurance.policy.domain.policy;

/** 진료 구분. 자기부담금 구조가 다르다 — 입원은 최소공제가 없고 정률만 적용된다. */
public enum TreatmentType {
    INPATIENT("입원"),
    OUTPATIENT("통원"),
    PRESCRIPTION("처방조제");

    private final String label;

    TreatmentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
