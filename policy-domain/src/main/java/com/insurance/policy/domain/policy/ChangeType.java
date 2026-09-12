package com.insurance.policy.domain.policy;

/**
 * 이력 레코드가 생긴 이유.
 *
 * <p><b>ENDORSEMENT와 CORRECTION의 구분이 이 시스템의 핵심이다.</b>
 * <ul>
 *   <li>{@link #ENDORSEMENT} — "오늘부터 바뀐다". 과거 스냅샷은 변하지 않는다</li>
 *   <li>{@link #CORRECTION} — "과거가 원래 그랬다". 과거 스냅샷이 바뀌고,
 *       {@code policy.corrected}가 claims의 재심사를 유발한다</li>
 * </ul>
 */
public enum ChangeType {
    CREATE("최초 생성"),
    ENDORSEMENT("계약 변경"),
    CORRECTION("소급 정정");

    private final String label;

    ChangeType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
