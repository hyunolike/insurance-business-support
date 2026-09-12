package com.insurance.policy.domain.policy;

import java.util.Objects;

/** 상품 참조. 상품 마스터의 상세는 이 컨텍스트가 소유하지 않는다. */
public record ProductRef(String productCode, String productName, Generation generation) {

    public ProductRef {
        Objects.requireNonNull(productCode, "상품코드는 필수입니다.");
        Objects.requireNonNull(productName, "상품명은 필수입니다.");
        Objects.requireNonNull(generation, "세대는 필수입니다.");
    }
}
