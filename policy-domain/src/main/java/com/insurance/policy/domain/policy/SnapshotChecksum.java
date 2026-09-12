package com.insurance.policy.domain.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 스냅샷 체크섬.
 *
 * <p>claims가 저장한 스냅샷이 나중에 변조되지 않았는지 확인하는 값이다.
 * claims는 심사할 때마다 저장된 원문으로 이 값을 다시 계산해 대조하고,
 * 불일치하면 심사를 중단하고 수동심사로 회부한다(R-POL-011).
 *
 * <p>계산 대상은 <b>체크섬 필드를 제외한 응답 본문의 정규화 JSON</b>이다.
 * 정규화 = 키 정렬 + 공백 제거. 두 레포가 같은 규칙을 쓰는지는 계약 테스트가 검증한다.
 *
 * <p>형식: {@code sha256:<64자리 hex>}
 */
public final class SnapshotChecksum {

    private static final String PREFIX = "sha256:";

    private final String value;

    private SnapshotChecksum(String value) {
        this.value = value;
    }

    /** 정규화된 JSON 문자열로부터 체크섬을 계산한다. */
    public static SnapshotChecksum of(String canonicalJson) {
        Objects.requireNonNull(canonicalJson, "정규화 JSON은 필수입니다.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return new SnapshotChecksum(PREFIX + HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JRE가 제공해야 하는 알고리즘이다.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public static SnapshotChecksum parse(String value) {
        Objects.requireNonNull(value, "체크섬은 필수입니다.");
        if (!value.startsWith(PREFIX) || value.length() != PREFIX.length() + 64) {
            throw new IllegalArgumentException("체크섬 형식이 올바르지 않습니다: " + value);
        }
        return new SnapshotChecksum(value);
    }

    public boolean matches(String canonicalJson) {
        return this.equals(of(canonicalJson));
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SnapshotChecksum other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
