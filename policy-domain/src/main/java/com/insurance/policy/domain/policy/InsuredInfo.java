package com.insurance.policy.domain.policy;

import java.util.Objects;

/**
 * 스냅샷에 실리는 피보험자 정보.
 *
 * <p><b>최소권한.</b> 성명·연락처·주민등록번호는 담지 않는다.
 * claims가 심사에 필요한 것은 식별자와 (연령 기반 룰을 위한) 출생연도뿐이다.
 * 전체 생년월일조차 불필요하다.
 */
public record InsuredInfo(InsuredRef insuredRef, int birthYear, Relation relationToHolder) {

    public InsuredInfo {
        Objects.requireNonNull(insuredRef, "피보험자 식별자는 필수입니다.");
        Objects.requireNonNull(relationToHolder, "계약자와의 관계는 필수입니다.");
        if (birthYear < 1900 || birthYear > 2200) {
            throw new IllegalArgumentException("출생연도가 범위를 벗어났습니다: " + birthYear);
        }
    }

    /** 계약자와 피보험자의 관계. */
    public enum Relation {
        SELF("본인"),
        SPOUSE("배우자"),
        CHILD("자녀"),
        PARENT("부모"),
        OTHER("기타");

        private final String label;

        Relation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
