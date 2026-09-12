package com.insurance.policy.domain.policy.event;

import static com.insurance.policy.domain.policy.PolicyFixtures.POLICY_NO;
import static com.insurance.policy.domain.policy.PolicyFixtures.급여입원;
import static com.insurance.policy.domain.policy.PolicyFixtures.insured;
import static com.insurance.policy.domain.policy.PolicyFixtures.period;
import static com.insurance.policy.domain.policy.PolicyFixtures.product;
import static com.insurance.policy.domain.policy.PolicyFixtures.계약일;
import static com.insurance.policy.domain.policy.PolicyFixtures.최초기록;
import static org.assertj.core.api.Assertions.assertThat;

import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.shared.DomainEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 이벤트 페이로드 — <b>외부로 공표되는 스키마</b>.
 *
 * <p>이 테스트가 존재하는 이유는 두 가지다.
 *
 * <p><b>1. 실제로 전부 깨져 있었다.</b> Outbox 어댑터가 이벤트 객체를 리플렉션으로
 * 직렬화하던 시절, {@code EventId}에 게터가 없어 모든 이벤트 적재가 실패했다.
 * 통합 테스트가 Docker 부재로 로컬에서 skip돼 CI가 처음 돌 때까지 드러나지 않았다.
 *
 * <p><b>2. 민감정보가 새는 통로다.</b> 페이로드는 Kafka를 통해 컨텍스트 밖으로 나가고,
 * 한 번 나가면 회수할 수 없다. 그래서 "무엇이 실리는가"를 여기에 못 박아,
 * 페이로드가 조용히 늘어나면 테스트가 깨지도록 한다.
 */
@DisplayName("이벤트 페이로드")
class PolicyEventPayloadTest {

    static List<DomainEvent> allEvents() {
        return List.of(issued(), endorsed(), corrected(), statusChanged());
    }

    static PolicyIssued issued() {
        return new PolicyIssued(POLICY_NO, product(), insured().insuredRef(), period(),
                계약일, List.of(급여입원), true, 최초기록);
    }

    static PolicyEndorsed endorsed() {
        return new PolicyEndorsed(POLICY_NO, List.of(급여입원), LocalDate.of(2026, 6, 1),
                "가입금액 변경", "OP-1001", 최초기록);
    }

    static PolicyCorrected corrected() {
        return new PolicyCorrected(POLICY_NO, 계약일, LocalDate.of(2031, 1, 1),
                List.of("EXCLUSION"), 1, 2, "착오 등록", "OP-1001", "MGR-2001", 최초기록);
    }

    static PolicyStatusChanged statusChanged() {
        return new PolicyStatusChanged(POLICY_NO, PolicyStatus.GRACE, PolicyStatus.LAPSED,
                LocalDate.of(2026, 7, 1), "보험료 미납", "BATCH", 최초기록);
    }

    @Nested
    @DisplayName("공통 규칙")
    class CommonRules {

        /**
         * ★ 이 검증이 원래의 버그를 잡는다.
         *
         * <p>도메인 모듈에는 Jackson이 없으므로 직렬화 자체는 여기서 못 돌린다.
         * 대신 "JSON 기본형만 들어 있다"를 확인한다 — VO나 enum이 섞이는 순간 걸린다.
         * 실제 직렬화는 {@code OutboxEnvelopePayloadTest}가 본다.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.insurance.policy.domain.policy.event."
                + "PolicyEventPayloadTest#allEvents")
        @DisplayName("JSON 기본형만 담는다 — VO·enum·날짜 타입 금지")
        void shouldContainOnlyJsonPrimitives(DomainEvent event) {
            event.payload().forEach((key, value) ->
                    assertJsonPrimitive(event.eventType() + "." + key, value));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.insurance.policy.domain.policy.event."
                + "PolicyEventPayloadTest#allEvents")
        @DisplayName("애그리거트를 찾을 수 있다")
        void shouldCarryPolicyNo(DomainEvent event) {
            assertThat(event.payload()).containsEntry("policyNo", POLICY_NO.value());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.insurance.policy.domain.policy.event."
                + "PolicyEventPayloadTest#allEvents")
        @DisplayName("봉투 헤더와 중복되는 값은 넣지 않는다")
        void shouldNotDuplicateEnvelopeHeader(DomainEvent event) {
            assertThat(event.payload())
                    .as("eventId·eventType·occurredAt은 봉투가 갖는다")
                    .doesNotContainKeys("eventId", "eventType", "occurredAt");
        }

        private void assertJsonPrimitive(String path, Object value) {
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean) {
                return;
            }
            if (value instanceof List<?> list) {
                list.forEach(item -> assertJsonPrimitive(path + "[]", item));
                return;
            }
            throw new AssertionError(
                    "%s 에 JSON 기본형이 아닌 값이 있습니다: %s. 내부 타입 구조가 공표된다."
                            .formatted(path, value.getClass().getName()));
        }
    }

    @Test
    @DisplayName("policy.issued — 부담보 상세는 싣지 않고 존재 여부만 알린다")
    void issuedPayload() {
        Map<String, Object> p = issued().payload();

        assertThat(p).containsEntry("policyNo", "P2026-0001234")
                .containsEntry("insuredRef", insured().insuredRef().value())
                .containsEntry("effectiveDate", "2026-01-01")
                .containsEntry("coverageCodes", List.of(급여입원.value()))
                .containsEntry("hasExclusions", true);

        assertThat(p.keySet())
                .as("KCD 범위는 건강정보다. 필요하면 claims가 스냅샷 API로 인가받아 가져간다")
                .doesNotContain("exclusions", "kcdRanges");
    }

    @Test
    @DisplayName("policy.endorsed — 바뀐 담보 코드만, 조건 값은 싣지 않는다")
    void endorsedPayload() {
        Map<String, Object> p = endorsed().payload();

        assertThat(p).containsEntry("affectedCoverages", List.of(급여입원.value()))
                .containsEntry("effectiveFrom", "2026-06-01")
                .containsEntry("actorRef", "OP-1001");

        assertThat(p.keySet()).doesNotContain("terms", "insuredAmount");
    }

    @Test
    @DisplayName("★ policy.corrected — claims가 재심사 대상을 고를 수 있는 만큼만")
    void correctedPayload() {
        Map<String, Object> p = corrected().payload();

        assertThat(p).containsEntry("scopeValidFrom", "2026-01-01")
                .containsEntry("scopeValidTo", "2031-01-01")
                .containsEntry("affectedElements", List.of("EXCLUSION"))
                .containsEntry("previousSnapshotVersion", 1)
                .containsEntry("newSnapshotVersion", 2)
                .as("과거를 바꾸는 행위이므로 승인자가 남는다")
                .containsEntry("approvedBy", "MGR-2001");

        assertThat(p.keySet())
                .as("정정 전후 값 자체는 곧 KCD 범위다. claims는 스냅샷을 두 번 불러 차이를 본다")
                .doesNotContain("before", "after", "kcdRanges");
    }

    @Test
    @DisplayName("policy.corrected — 종기 없는 부담보면 scopeValidTo가 null이다")
    void correctedPayloadWithOpenEndedScope() {
        var event = new PolicyCorrected(POLICY_NO, 계약일, null, List.of("EXCLUSION"),
                1, 2, "착오 등록", "OP-1001", "MGR-2001", 최초기록);

        assertThat(event.payload()).containsEntry("scopeValidTo", null);
    }

    @Test
    @DisplayName("policy.lapsed — 토픽이 갈려도 정확한 전이를 함께 싣는다")
    void statusChangedPayload() {
        var event = statusChanged();

        assertThat(event.eventType()).isEqualTo("policy.lapsed");
        assertThat(event.payload()).containsEntry("fromStatus", "GRACE")
                .containsEntry("toStatus", "LAPSED")
                .containsEntry("effectiveFrom", "2026-07-01")
                .containsEntry("reason", "보험료 미납");
    }
}
