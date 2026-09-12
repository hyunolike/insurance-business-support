/*
 * ─────────────────────────────────────────────────────────────────────────────
 * 의도적 중복 (Shared Kernel)
 *
 * claims-platform 에도 같은 형태의 타입이 있다. 공유 라이브러리로 빼지 않는 것은
 * 결정이지 게으름이 아니다 — 공유 모듈은 두 바운디드 컨텍스트를 컴파일 타임에
 * 다시 묶어버려서, 컨텍스트를 나눈 의미를 없앤다. 한쪽의 필요로 타입이 바뀌면
 * 다른 쪽이 원치 않는 변경을 강제로 받는다.
 *
 * 대신 두 레포가 주고받는 계약(스냅샷 응답, 이벤트 스키마)은 소비자 주도
 * 계약 테스트로 맞춘다. 결합 없이 안전성만 가져가는 방식이다.
 *
 * docs/design/01-context-map.md §8.1
 * ─────────────────────────────────────────────────────────────────────────────
 */
package com.insurance.policy.domain.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;

/**
 * 이벤트 식별자 (ULID).
 *
 * <p>UUIDv4 대신 ULID를 쓰는 이유:
 * <ul>
 *   <li>시간순 정렬이 가능해 Outbox 재발행 시 순서 복원이 쉽다</li>
 *   <li>단조 증가라 DB 인덱스 지역성이 좋다 (UUIDv4는 삽입 위치가 흩어진다)</li>
 *   <li>26자 고정 길이 문자열이라 로그·URL에서 다루기 쉽다</li>
 * </ul>
 *
 * <p>도메인 모듈은 외부 의존성이 없으므로 JDK만으로 구현한다.
 * (48비트 타임스탬프 + 80비트 랜덤, Crockford Base32)
 */
public final class EventId {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 26;
    private static final int TIME_LENGTH = 10;
    private static final int RANDOM_BYTES = 10;

    private final String value;

    private EventId(String value) {
        this.value = value;
    }

    /** 현재 시각 기준으로 새 식별자를 생성한다. */
    public static EventId generate() {
        return generate(Instant.now());
    }

    /** 테스트에서 시간을 고정하기 위한 진입점. */
    public static EventId generate(Instant at) {
        Objects.requireNonNull(at, "생성 시각은 필수입니다.");
        byte[] entropy = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(entropy);
        return new EventId(encodeTime(at.toEpochMilli()) + encodeRandom(entropy));
    }

    /** 저장된 문자열을 복원한다. 형식이 다르면 거부한다. */
    public static EventId from(String value) {
        Objects.requireNonNull(value, "이벤트 식별자는 필수입니다.");
        if (value.length() != LENGTH) {
            throw new IllegalArgumentException(
                    "이벤트 식별자는 26자여야 합니다: 입력 길이=" + value.length());
        }
        for (int i = 0; i < LENGTH; i++) {
            if (indexOfSymbol(value.charAt(i)) < 0) {
                throw new IllegalArgumentException(
                        "이벤트 식별자에 허용되지 않은 문자가 있습니다: " + value.charAt(i));
            }
        }
        return new EventId(value);
    }

    /** 이 식별자가 인코딩하고 있는 생성 시각. */
    public Instant timestamp() {
        long millis = 0L;
        for (int i = 0; i < TIME_LENGTH; i++) {
            millis = (millis << 5) | indexOfSymbol(value.charAt(i));
        }
        return Instant.ofEpochMilli(millis);
    }

    public String value() {
        return value;
    }

    private static String encodeTime(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("1970년 이전 시각은 인코딩할 수 없습니다.");
        }
        char[] out = new char[TIME_LENGTH];
        long remaining = millis;
        for (int i = TIME_LENGTH - 1; i >= 0; i--) {
            out[i] = ENCODING[(int) (remaining & 0x1F)];
            remaining >>>= 5;
        }
        if (remaining != 0) {
            throw new IllegalArgumentException("표현 범위를 넘는 시각입니다: " + millis);
        }
        return new String(out);
    }

    private static String encodeRandom(byte[] entropy) {
        // 80비트를 5비트씩 16개 심볼로 인코딩한다.
        StringBuilder out = new StringBuilder(16);
        long high = 0L;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (entropy[i] & 0xFFL);
        }
        long low = 0L;
        for (int i = 5; i < 10; i++) {
            low = (low << 8) | (entropy[i] & 0xFFL);
        }
        appendBase32(out, high);
        appendBase32(out, low);
        return out.toString();
    }

    private static void appendBase32(StringBuilder out, long fortyBits) {
        char[] chunk = new char[8];
        long remaining = fortyBits;
        for (int i = 7; i >= 0; i--) {
            chunk[i] = ENCODING[(int) (remaining & 0x1F)];
            remaining >>>= 5;
        }
        out.append(chunk);
    }

    private static int indexOfSymbol(char c) {
        for (int i = 0; i < ENCODING.length; i++) {
            if (ENCODING[i] == c) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventId other)) {
            return false;
        }
        return value.equals(other.value);
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
