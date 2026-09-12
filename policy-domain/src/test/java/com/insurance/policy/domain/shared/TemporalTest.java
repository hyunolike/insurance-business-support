package com.insurance.policy.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 시점 재현성 검증.
 *
 * <p>이 시스템의 <b>절대 요구사항</b>이다. 여기가 깨지면 claims의 심사 근거가 무너지고,
 * 두 레포를 나눈 설계 전체가 의미를 잃는다.
 */
@DisplayName("Temporal (Bitemporal 시점 판정)")
class TemporalTest {

    private static final LocalDate 계약일 = LocalDate.of(2026, 1, 1);
    private static final LocalDate 사고일 = LocalDate.of(2026, 3, 14);
    private static final LocalDate 만기일 = LocalDate.of(2031, 1, 1);

    private static final Instant 기록시점 = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant 청구접수시점 = Instant.parse("2026-04-02T10:15:00Z");
    private static final Instant 정정시점 = Instant.parse("2026-05-20T11:00:00Z");
    private static final Instant 재심사시점 = Instant.parse("2026-06-01T09:00:00Z");

    @Nested
    @DisplayName("유효시간 축")
    class ValidTime {

        @Test
        @DisplayName("구간 안이면 유효하다")
        void shouldBeValidInsideRange() {
            var fact = new Fact(계약일, 만기일, 기록시점, null);

            assertThat(fact.isValidOn(사고일)).isTrue();
            assertThat(fact.isValidOn(계약일)).as("시작일은 포함").isTrue();
        }

        @Test
        @DisplayName("종료일은 배타적이다 — 만기일 당일 사고는 이 구간에 속하지 않는다")
        void shouldTreatEndExclusive() {
            var fact = new Fact(계약일, 만기일, 기록시점, null);

            assertThat(fact.isValidOn(만기일.minusDays(1))).isTrue();
            assertThat(fact.isValidOn(만기일)).isFalse();
        }

        @Test
        @DisplayName("시작일 이전이면 유효하지 않다 — 책임개시일 이전 사고는 부보 대상이 아니다")
        void shouldNotBeValidBeforeStart() {
            var fact = new Fact(계약일, 만기일, 기록시점, null);

            assertThat(fact.isValidOn(계약일.minusDays(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("기록시간 축")
    class TransactionTime {

        @Test
        @DisplayName("기록되기 전 시점에서는 알 수 없었다")
        void shouldNotBeKnownBeforeRecording() {
            var fact = new Fact(계약일, 만기일, 청구접수시점, null);

            assertThat(fact.wasKnownAt(기록시점)).isFalse();
            assertThat(fact.wasKnownAt(청구접수시점)).isTrue();
        }

        @Test
        @DisplayName("정정되지 않았으면 계속 유효한 기록이다")
        void shouldRemainKnownWhenNotSuperseded() {
            var fact = new Fact(계약일, 만기일, 기록시점, null);

            assertThat(fact.wasKnownAt(재심사시점)).isTrue();
            assertThat(fact.isCurrentRecord()).isTrue();
        }

        @Test
        @DisplayName("정정 시점 이후에는 더 이상 유효한 기록이 아니다")
        void shouldStopBeingKnownAfterSupersession() {
            var fact = new Fact(계약일, 만기일, 기록시점, 정정시점);

            assertThat(fact.wasKnownAt(청구접수시점)).as("정정 전에는 알고 있었다").isTrue();
            assertThat(fact.wasKnownAt(정정시점)).as("정정 시점부터는 아니다").isFalse();
            assertThat(fact.wasKnownAt(재심사시점)).isFalse();
            assertThat(fact.isCurrentRecord()).isFalse();
        }
    }

    @Test
    @DisplayName("★ 소급 정정이 있어도 정정 전 판단을 재현할 수 있다")
    void shouldReproducePastJudgementAfterCorrection() {
        // 2026-01-01에 척추 부담보를 등록했다가, 2026-05-20에 착오였음이 밝혀져 정정
        var 부담보 = new Fact(계약일, 만기일, 기록시점, 정정시점);

        // 4월 2일 청구 접수 시점: 부담보가 있는 것으로 알고 있었다 → 부지급
        assertThat(부담보.isEffectiveOn(사고일, 청구접수시점))
                .as("4월의 부지급 근거가 재현되어야 한다")
                .isTrue();

        // 6월 1일 재심사 시점: 정정 후이므로 부담보가 없다 → 재검토 대상
        assertThat(부담보.isEffectiveOn(사고일, 재심사시점))
                .as("지금 아는 진실로는 부담보가 없다")
                .isFalse();

        // 같은 asOf인데 knownAt에 따라 답이 다르다. 이것이 Bitemporal의 존재 이유다.
    }

    @Test
    @DisplayName("변경(Endorsement)은 과거 시점 조회에 영향을 주지 않는다")
    void shouldNotAffectPastWhenEndorsed() {
        // 2026-06-01부터 가입금액 변경 → 기존 구간은 그날 종료되고 새 구간이 시작된다
        LocalDate 변경일 = LocalDate.of(2026, 6, 1);
        Instant 변경기록시점 = Instant.parse("2026-06-01T09:00:00Z");

        var 구버전 = new Fact(계약일, 변경일, 기록시점, null);
        var 신버전 = new Fact(변경일, 만기일, 변경기록시점, null);

        // 사고일(3/14) 기준으로는 언제 조회하든 구버전이다
        assertThat(구버전.isEffectiveOn(사고일, 청구접수시점)).isTrue();
        assertThat(구버전.isEffectiveOn(사고일, 재심사시점))
                .as("변경은 과거를 바꾸지 않는다")
                .isTrue();
        assertThat(신버전.isEffectiveOn(사고일, 재심사시점)).isFalse();

        // 변경 이후 사고라면 신버전
        assertThat(신버전.isEffectiveOn(LocalDate.of(2026, 7, 1), 재심사시점)).isTrue();
        assertThat(구버전.isEffectiveOn(LocalDate.of(2026, 7, 1), 재심사시점)).isFalse();
    }

    @Test
    @DisplayName("두 축이 모두 만족해야 유효하다")
    void shouldRequireBothAxes() {
        var fact = new Fact(계약일, 만기일, 청구접수시점, 정정시점);

        // 유효시간 OK, 기록시간 OK
        assertThat(fact.isEffectiveOn(사고일, Instant.parse("2026-04-10T00:00:00Z"))).isTrue();
        // 유효시간 NG (계약 전 사고)
        assertThat(fact.isEffectiveOn(LocalDate.of(2025, 12, 1),
                Instant.parse("2026-04-10T00:00:00Z"))).isFalse();
        // 기록시간 NG (기록 전)
        assertThat(fact.isEffectiveOn(사고일, 기록시점)).isFalse();
        // 기록시간 NG (정정 후)
        assertThat(fact.isEffectiveOn(사고일, 재심사시점)).isFalse();
    }

    /** 테스트용 Temporal 구현. 실제 Coverage/Exclusion은 Phase 1에서 만든다. */
    private record Fact(LocalDate validFrom, LocalDate validTo,
                        Instant recordedAt, Instant supersededAt) implements Temporal {
    }
}
