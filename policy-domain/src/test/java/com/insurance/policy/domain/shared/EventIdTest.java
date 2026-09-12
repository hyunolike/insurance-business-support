package com.insurance.policy.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventId (ULID)")
class EventIdTest {

    @Test
    @DisplayName("26자 Crockford Base32로 생성된다")
    void shouldGenerateFixedLength() {
        assertThat(EventId.generate().value())
                .hasSize(26)
                .matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    @DisplayName("생성 시각을 되읽을 수 있다")
    void shouldEncodeTimestamp() {
        Instant at = Instant.parse("2026-04-02T10:15:00Z");
        assertThat(EventId.generate(at).timestamp()).isEqualTo(at);
    }

    @Test
    @DisplayName("시간순으로 정렬된다 — Outbox 재발행 시 순서 복원의 근거")
    void shouldSortChronologically() {
        Instant base = Instant.parse("2026-04-02T10:15:00Z");
        EventId first = EventId.generate(base);
        EventId second = EventId.generate(base.plusMillis(1));
        EventId third = EventId.generate(base.plusSeconds(60));

        List<String> sorted = new ArrayList<>(
                List.of(third.value(), first.value(), second.value()));
        sorted.sort(String::compareTo);

        assertThat(sorted).containsExactly(first.value(), second.value(), third.value());
    }

    @Test
    @DisplayName("같은 밀리초에 생성해도 충돌하지 않는다")
    void shouldNotCollideWithinSameMillisecond() {
        Instant fixed = Instant.parse("2026-04-02T10:15:00Z");
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            generated.add(EventId.generate(fixed).value());
        }
        assertThat(generated).hasSize(10_000);
    }

    @Test
    @DisplayName("저장된 문자열을 복원한다")
    void shouldRestoreFromString() {
        EventId original = EventId.generate();
        assertThat(EventId.from(original.value())).isEqualTo(original);
    }

    @Test
    @DisplayName("형식이 틀리면 거부한다")
    void shouldRejectMalformedValue() {
        assertThatThrownBy(() -> EventId.from("TOO-SHORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("26자");

        // 'I', 'L', 'O', 'U' 는 Crockford Base32에서 제외된 문자다
        assertThatThrownBy(() -> EventId.from("01JBX7K3QM8W2ZP4NRTV9C6DYI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은 문자");

        assertThatThrownBy(() -> EventId.from(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("생성 시각이 null이면 거부한다")
    void shouldRejectNullInstant() {
        assertThatThrownBy(() -> EventId.generate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("1970년 이전 시각은 인코딩할 수 없다")
    void shouldRejectPreEpochInstant() {
        assertThatThrownBy(() -> EventId.generate(Instant.parse("1969-12-31T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1970년 이전");
    }

    @Test
    @DisplayName("동등성과 표시")
    void shouldSupportEqualityAndToString() {
        EventId id = EventId.generate();
        EventId same = EventId.from(id.value());
        EventId other = EventId.generate();

        assertThat(id).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(id).isNotEqualTo(other);
        assertThat(id).isNotEqualTo("string");
        assertThat(id).isNotEqualTo(null);
        assertThat(id).isEqualTo(id);
        assertThat(id).hasToString(id.value());
    }
}
