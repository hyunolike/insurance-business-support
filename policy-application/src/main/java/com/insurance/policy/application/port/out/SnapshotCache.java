package com.insurance.policy.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 스냅샷 응답 캐시.
 *
 * <p><b>캐시하는 값은 이미 렌더된 응답 JSON 문자열이다.</b> 도메인 객체
 * ({@code PolicySnapshot})를 캐시하지 않는다. 두 가지 이유가 있다.
 * <ul>
 *   <li>도메인 모듈에는 Jackson이 없다. 직렬화하려면 리플렉션에 맡겨야 하고,
 *       그것이 Outbox에서 이미 한 번 터진 함정이다 — VO에 게터가 없어 실패하거나,
 *       성공해도 내부 구조에 캐시가 묶인다.</li>
 *   <li>응답 JSON은 이미 공표된 표현이다. 체크섬도 이 바이트에서 나오므로,
 *       캐시 적중과 미적중이 <b>바이트 단위로 같은 응답</b>을 준다.</li>
 * </ul>
 *
 * <h2>★ 무엇을 캐시해도 안전한가</h2>
 * <b>{@code knownAt}이 명시된 과거 조회만.</b> 그 답은 영원히 변하지 않는다.
 *
 * <p>이력은 append-only다. 정정이 일어나면 기존 행에 {@code superseded_at}이 찍히고
 * 새 행이 들어가는데, {@code superseded_at}은 <b>정정 시점</b>이다. 따라서
 * {@code knownAt < 정정시점}인 질의는 정정 전과 똑같은 행 집합을 본다.
 * 두 시간축이 모두 고정되면 답도 고정된다 — 무효화가 아예 필요 없다.
 *
 * <p>반대로 {@code knownAt}을 생략한 조회는 "지금 아는 진실"이라 내일 달라질 수 있다.
 * <b>그것은 캐시하지 않는다.</b> 무효화 로직으로 막을 수도 있지만, 그 로직이 한 번
 * 새면 claims가 틀린 근거로 심사한다. 이 시스템에서 그것은 가장 나쁜 결과다.
 * 반복 조회가 잦은 쪽은 어차피 재심사 경로(= knownAt 명시)다.
 */
public interface SnapshotCache {

    /**
     * @return 캐시된 응답 JSON. 없거나 캐시 저장소에 문제가 있으면 {@link Optional#empty()}
     */
    Optional<String> find(Key key);

    /** 저장에 실패해도 예외를 던지지 않는다 — 캐시는 있으면 좋은 것이지 필수가 아니다. */
    void put(Key key, String responseJson);

    /**
     * 캐시 키.
     *
     * <p>{@code insuredRef}가 키에 들어간다. 빼면 피보험자가 다른 요청이 남의 계약
     * 스냅샷을 받는다 — 서비스가 404로 막는 검사를 캐시가 우회시키는 셈이다.
     */
    record Key(String policyNo, String insuredRef, LocalDate asOf, Instant knownAt) {

        public Key {
            if (knownAt == null) {
                throw new IllegalArgumentException(
                        "knownAt이 없는 조회는 캐시 대상이 아닙니다. 답이 나중에 달라질 수 있습니다.");
            }
        }

        /** Redis 키 문자열. 콜론 구분은 Redis 관례이고 네임스페이스 스캔에 편하다. */
        public String asRedisKey() {
            return "policy:snapshot:v1:%s:%s:%s:%s"
                    .formatted(policyNo, insuredRef, asOf, knownAt.toEpochMilli());
        }
    }
}
