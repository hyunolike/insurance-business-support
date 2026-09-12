package com.insurance.policy;

import static com.insurance.policy.domain.policy.PolicyFixtures.POLICY_NO;
import static com.insurance.policy.domain.policy.PolicyFixtures.급여입원;
import static com.insurance.policy.domain.policy.PolicyFixtures.insured;
import static com.insurance.policy.domain.policy.PolicyFixtures.period;
import static com.insurance.policy.domain.policy.PolicyFixtures.product;
import static com.insurance.policy.domain.policy.PolicyFixtures.계약일;
import static com.insurance.policy.domain.policy.PolicyFixtures.최초기록;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.policy.event.PolicyCorrected;
import com.insurance.policy.domain.policy.event.PolicyEndorsed;
import com.insurance.policy.domain.policy.event.PolicyIssued;
import com.insurance.policy.domain.policy.event.PolicyStatusChanged;
import com.insurance.policy.domain.shared.DomainEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 이벤트 봉투가 <b>실제로 직렬화되는지</b> 검증한다.
 *
 * <p>페이로드의 내용과 "JSON 기본형만 담는다"는 규칙은 도메인 단위 테스트
 * ({@code PolicyEventPayloadTest})가 본다. 여기서만 할 수 있는 것은 하나다 —
 * <b>진짜 Jackson으로 돌려보는 것</b>. 도메인 모듈에는 Jackson이 없기 때문이다.
 *
 * <p>원래의 사고가 정확히 이 지점이었다. 어댑터가 이벤트 객체를 리플렉션으로
 * 직렬화했고 {@code EventId}에 게터가 없어 모든 적재가 실패했는데,
 * 통합 테스트가 Docker 부재로 skip돼 CI가 처음 돌 때까지 아무도 몰랐다.
 * <b>이 테스트는 Docker가 필요 없다.</b> 직렬화 문제는 DB 없이 드러나야 한다.
 */
@DisplayName("이벤트 봉투 직렬화")
class OutboxEnvelopePayloadTest {

    /** 스프링 컨텍스트의 매퍼 설정과 무관하게, 기본 설정에서도 통과해야 한다. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static List<DomainEvent> allEvents() {
        return List.of(
                new PolicyIssued(POLICY_NO, product(), insured().insuredRef(), period(),
                        계약일, List.of(급여입원), true, 최초기록),
                new PolicyEndorsed(POLICY_NO, List.of(급여입원), LocalDate.of(2026, 6, 1),
                        "가입금액 변경", "OP-1001", 최초기록),
                new PolicyCorrected(POLICY_NO, 계약일, LocalDate.of(2031, 1, 1),
                        List.of("EXCLUSION"), 1, 2, "착오 등록", "OP-1001", "MGR-2001", 최초기록),
                new PolicyStatusChanged(POLICY_NO, PolicyStatus.GRACE, PolicyStatus.LAPSED,
                        LocalDate.of(2026, 7, 1), "보험료 미납", "BATCH", 최초기록));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEvents")
    @DisplayName("★ 봉투 전체가 Jackson으로 직렬화된다")
    void shouldSerializeEnvelope(DomainEvent event) throws Exception {
        // 어댑터(OutboxAppenderAdapter.serializeEnvelope)가 만드는 것과 같은 모양
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().value());
        envelope.put("eventType", event.eventType());
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("producer", "business-support");
        envelope.put("aggregateId", event.aggregateId());
        envelope.put("payload", event.payload());

        String json = MAPPER.writeValueAsString(envelope);

        assertThat(json)
                .contains("\"eventType\":\"" + event.eventType() + "\"")
                .contains("\"policyNo\":\"" + POLICY_NO.value() + "\"");
    }

    /**
     * 새 이벤트를 추가하면 이 테스트가 깨진다. 그것이 목적이다.
     *
     * <p>위 검증은 {@link #allEvents()}에 적힌 것만 본다. 이벤트를 추가하면서 여기에
     * 넣는 것을 잊으면 검증 없이 발행되므로, 목록이 실제 구현체 집합과 같은지 확인한다.
     */
    @Test
    @DisplayName("모든 DomainEvent 구현체가 등록되어 있다")
    void shouldCoverEveryEventType() {
        Set<String> implementations = new ClassFileImporter()
                .importPackages("com.insurance.policy.domain.policy.event").stream()
                .filter(c -> c.isAssignableTo(DomainEvent.class))
                .filter(c -> !c.isInterface())
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        Set<String> covered = allEvents().stream()
                .map(e -> e.getClass().getName())
                .collect(Collectors.toSet());

        assertThat(implementations)
                .as("새 이벤트를 추가했다면 allEvents()에도 추가한다")
                .isNotEmpty()
                .isEqualTo(covered);
    }
}
