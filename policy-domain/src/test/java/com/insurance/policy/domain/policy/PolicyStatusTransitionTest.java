package com.insurance.policy.domain.policy;

import static com.insurance.policy.domain.policy.PolicyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurance.policy.domain.policy.event.PolicyStatusChanged;
import com.insurance.policy.domain.policy.exception.IllegalPolicyTransitionException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("계약 상태 전이")
class PolicyStatusTransitionTest {

    private static final LocalDate 미납일 = LocalDate.of(2026, 4, 1);
    private static final LocalDate 실효일 = LocalDate.of(2026, 6, 1);
    private static final Instant 미납기록 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant 실효기록 = Instant.parse("2026-06-01T00:00:00Z");

    @Nested
    @DisplayName("전이표")
    class TransitionTable {

        @ParameterizedTest(name = "{0} → {1} = {2}")
        @CsvSource({
                "APPLIED,      UNDERWRITING, true",
                "APPLIED,      IN_FORCE,     false",
                "UNDERWRITING, IN_FORCE,     true",
                "UNDERWRITING, DECLINED,     true",
                "IN_FORCE,     GRACE,        true",
                "IN_FORCE,     SURRENDERED,  true",
                "IN_FORCE,     MATURED,      true",
                "IN_FORCE,     LAPSED,       false",
                "GRACE,        IN_FORCE,     true",
                "GRACE,        LAPSED,       true",
                "LAPSED,       REINSTATED,   true",
                "LAPSED,       IN_FORCE,     false",
                "REINSTATED,   IN_FORCE,     true",
                "MATURED,      IN_FORCE,     false",
                "CANCELLED,    IN_FORCE,     false",
                "DECLINED,     IN_FORCE,     false",
                "SURRENDERED,  IN_FORCE,     false"
        })
        @DisplayName("정의된 전이만 허용한다")
        void shouldAllowOnlyDefinedTransitions(PolicyStatus from, PolicyStatus to, boolean allowed) {
            assertThat(PolicyTransitions.isAllowed(from, to)).isEqualTo(allowed);
        }

        @Test
        @DisplayName("IN_FORCE에서 LAPSED로 바로 갈 수 없다 — 납입최고(GRACE)를 거쳐야 한다")
        void shouldRequireGraceBeforeLapse() {
            assertThat(PolicyTransitions.isAllowed(PolicyStatus.IN_FORCE, PolicyStatus.LAPSED))
                    .as("절차 없는 실효는 무효가 될 수 있다")
                    .isFalse();
            assertThat(PolicyTransitions.allowedFrom(PolicyStatus.IN_FORCE))
                    .contains(PolicyStatus.GRACE);
        }

        @Test
        @DisplayName("종결 상태에서는 아무 데도 갈 수 없다")
        void shouldHaveNoTransitionFromTerminal() {
            for (PolicyStatus terminal : new PolicyStatus[]{
                    PolicyStatus.DECLINED, PolicyStatus.SURRENDERED,
                    PolicyStatus.MATURED, PolicyStatus.CANCELLED}) {
                assertThat(PolicyTransitions.allowedFrom(terminal)).isEmpty();
                assertThat(terminal.isTerminal()).isTrue();
            }
        }

        @Test
        @DisplayName("전이 위반은 예외에 허용 목록을 담아 알려준다")
        void shouldExplainViolation() {
            assertThatThrownBy(() -> PolicyTransitions.ensureAllowed(
                    POLICY_NO, PolicyStatus.MATURED, PolicyStatus.IN_FORCE))
                    .isInstanceOf(IllegalPolicyTransitionException.class)
                    .hasMessageContaining("만기")
                    .hasMessageContaining("정상")
                    .satisfies(e -> {
                        var ex = (IllegalPolicyTransitionException) e;
                        assertThat(ex.policyNo()).isEqualTo(POLICY_NO);
                        assertThat(ex.from()).isEqualTo(PolicyStatus.MATURED);
                        assertThat(ex.to()).isEqualTo(PolicyStatus.IN_FORCE);
                    });
        }
    }

    @Nested
    @DisplayName("상태별 보상 가능성 — claims에 전달되는 사실")
    class CoverageSemantics {

        @Test
        @DisplayName("정상·부활은 보상 가능, 실효·해지·만기는 불가")
        void shouldExposeCoverageSemantics() {
            assertThat(PolicyStatus.IN_FORCE.coversAccident()).isTrue();
            assertThat(PolicyStatus.REINSTATED.coversAccident()).isTrue();
            assertThat(PolicyStatus.LAPSED.coversAccident()).isFalse();
            assertThat(PolicyStatus.SURRENDERED.coversAccident()).isFalse();
            assertThat(PolicyStatus.MATURED.coversAccident()).isFalse();
            assertThat(PolicyStatus.CANCELLED.coversAccident()).isFalse();
            assertThat(PolicyStatus.APPLIED.coversAccident()).isFalse();
        }

        @Test
        @DisplayName("유예기간(GRACE)은 보상 가능 — 다만 claims에서 회부된다")
        void graceStillCovers() {
            assertThat(PolicyStatus.GRACE.coversAccident())
                    .as("보상은 되지만 미납보험료 상계 판단이 필요해 R-POL-031로 회부된다")
                    .isTrue();
            assertThat(PolicyStatus.GRACE.label()).isEqualTo("납입최고");
        }
    }

    @Nested
    @DisplayName("애그리거트에서의 전이")
    class OnAggregate {

        @Test
        @DisplayName("정상 → 납입최고 → 실효")
        void shouldWalkLapseSequence() {
            var 계약 = 표준계약();
            계약.pullEvents();

            계약.changeStatus(PolicyStatus.GRACE, 미납일, "보험료 미납", "BATCH", 미납기록);
            assertThat(계약.currentStatus(미납일, 미납기록)).isEqualTo(PolicyStatus.GRACE);

            계약.changeStatus(PolicyStatus.LAPSED, 실효일, "유예기간 경과", "BATCH", 실효기록);
            assertThat(계약.currentStatus(실효일, 실효기록)).isEqualTo(PolicyStatus.LAPSED);
        }

        @Test
        @DisplayName("★ 실효돼도 실효 이전 사고의 스냅샷은 IN_FORCE다")
        void shouldPreservePastStatusAfterLapse() {
            var 계약 = 표준계약();
            계약.changeStatus(PolicyStatus.GRACE, 미납일, "보험료 미납", "BATCH", 미납기록);
            계약.changeStatus(PolicyStatus.LAPSED, 실효일, "유예기간 경과", "BATCH", 실효기록);

            assertThat(계약.snapshotAsOf(사고일, 실효기록).policyStatusAsOf())
                    .as("3월 사고는 정상 계약 상태에서 발생했다. 6월 실효가 이를 소급해 바꾸지 않는다")
                    .isEqualTo(PolicyStatus.IN_FORCE);

            assertThat(계약.snapshotAsOf(미납일, 실효기록).policyStatusAsOf())
                    .isEqualTo(PolicyStatus.GRACE);
            assertThat(계약.snapshotAsOf(실효일, 실효기록).policyStatusAsOf())
                    .isEqualTo(PolicyStatus.LAPSED);
        }

        @Test
        @DisplayName("허용되지 않은 전이는 거부한다 (→ HTTP 409)")
        void shouldRejectIllegalTransition() {
            var 계약 = 표준계약();

            assertThatThrownBy(() -> 계약.changeStatus(
                    PolicyStatus.LAPSED, 실효일, "바로 실효", "BATCH", 실효기록))
                    .isInstanceOf(IllegalPolicyTransitionException.class);
        }

        @Test
        @DisplayName("상태 변경일이 현재 구간 시작일보다 앞서면 거부한다")
        void shouldRejectBackdatedStatusChange() {
            var 계약 = 표준계약();

            assertThatThrownBy(() -> 계약.changeStatus(
                    PolicyStatus.GRACE, 계약일, "소급", "BATCH", 미납기록))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("뒤여야");
        }

        @Test
        @DisplayName("실효는 policy.lapsed, 해지·만기는 policy.terminated로 발행된다")
        void shouldMapEventTypeByTargetStatus() {
            var 계약 = 표준계약();
            계약.pullEvents();

            계약.changeStatus(PolicyStatus.GRACE, 미납일, "미납", "BATCH", 미납기록);
            assertThat(계약.pullEvents()).singleElement()
                    .isInstanceOfSatisfying(PolicyStatusChanged.class, e ->
                            assertThat(e.eventType()).isEqualTo("policy.status_changed"));

            계약.changeStatus(PolicyStatus.LAPSED, 실효일, "유예 경과", "BATCH", 실효기록);
            assertThat(계약.pullEvents()).singleElement()
                    .isInstanceOfSatisfying(PolicyStatusChanged.class, e -> {
                        assertThat(e.eventType()).isEqualTo("policy.lapsed");
                        assertThat(e.fromStatus()).isEqualTo(PolicyStatus.GRACE);
                        assertThat(e.toStatus()).isEqualTo(PolicyStatus.LAPSED);
                        assertThat(e.aggregateId()).isEqualTo(POLICY_NO.value());
                    });

            계약.changeStatus(PolicyStatus.REINSTATED, LocalDate.of(2026, 7, 1),
                    "부활", "CS", Instant.parse("2026-07-01T00:00:00Z"));
            assertThat(계약.pullEvents()).singleElement()
                    .isInstanceOfSatisfying(PolicyStatusChanged.class, e ->
                            assertThat(e.eventType()).isEqualTo("policy.reinstated"));
        }

        @Test
        @DisplayName("해지는 policy.terminated")
        void shouldEmitTerminated() {
            var 계약 = 표준계약();
            계약.pullEvents();

            계약.changeStatus(PolicyStatus.SURRENDERED, LocalDate.of(2026, 8, 1),
                    "계약자 해지", "CS", Instant.parse("2026-08-01T00:00:00Z"));

            assertThat(계약.pullEvents()).singleElement()
                    .isInstanceOfSatisfying(PolicyStatusChanged.class, e ->
                            assertThat(e.eventType()).isEqualTo("policy.terminated"));
        }
    }
}
