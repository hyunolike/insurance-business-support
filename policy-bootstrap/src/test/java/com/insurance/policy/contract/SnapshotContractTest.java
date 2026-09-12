package com.insurance.policy.contract;

import static com.insurance.policy.domain.policy.PolicyFixtures.POLICY_NO;
import static com.insurance.policy.domain.policy.PolicyFixtures.insured;
import static com.insurance.policy.domain.policy.PolicyFixtures.사고일;
import static com.insurance.policy.domain.policy.PolicyFixtures.부담보계약;
import static com.insurance.policy.domain.policy.PolicyFixtures.표준계약;
import static com.insurance.policy.domain.policy.PolicyFixtures.청구접수;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insurance.policy.adapter.web.snapshot.CanonicalJson;
import com.insurance.policy.adapter.web.snapshot.PolicySnapshotResponse;
import com.insurance.policy.domain.policy.Policy;
import com.insurance.policy.domain.policy.SnapshotChecksum;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ★ 계약 테스트 — claims가 선언한 기대를 이 저장소가 만족하는지 검증한다.
 *
 * <p><b>소비자 주도다.</b> 계약 파일({@code src/test/resources/contracts/})은 claims 레포에서
 * 동기화된 것이고, 이 저장소가 임의로 고쳐서는 안 된다. 고쳐야 한다면 claims와 합의한 뒤
 * 양쪽을 함께 바꾼다.
 *
 * <p><b>계약을 깨면 이 저장소의 빌드가 실패한다.</b> claims가 배포된 뒤 런타임에
 * 깨지는 것이 아니라, 여기 PR에서 잡힌다. 그것이 이 테스트의 존재 이유다.
 *
 * <p>Spring 컨텍스트도 DB도 띄우지 않는다. 계약은 <b>응답의 모양</b>에 관한 것이고,
 * 그것은 도메인 픽스처 → 응답 DTO 변환만으로 전부 드러난다. Docker 없이 돌아야
 * 누구나 로컬에서 계약 위반을 즉시 알 수 있다.
 *
 * @see docs/design/01-context-map.md §7
 */
@Tag("contract")
@DisplayName("계약 — claims 소비자 계약")
class SnapshotContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CanonicalJson CANONICAL = new CanonicalJson();

    /**
     * ★ claims가 저장한 스냅샷의 무결성을 이 값으로 확인한다.
     *
     * <p>정규화 규칙(키 정렬 + 공백 제거)이나 필드 표현이 한쪽에서만 바뀌면 여기서 깨진다.
     * 기대값은 claims가 계약 파일에 박아둔 것이므로, 이 테스트가 깨졌다는 것은
     * <b>이미 저장된 모든 스냅샷의 검증이 실패하게 된다</b>는 뜻이다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "policy-snapshot-response.json",
            "policy-snapshot-with-exclusion.json"
    })
    @DisplayName("★ 체크섬이 계약 파일의 기대값과 일치한다")
    void shouldMatchContractChecksum(String contractFile) throws Exception {
        JsonNode contract = readContract(contractFile);

        PolicySnapshotResponse actual = renderSnapshot(contract.get("$given").asText());
        String checksum = SnapshotChecksum.of(CANONICAL.of(actual)).value();

        assertThat(checksum)
                .as("체크섬이 달라지면 claims가 저장해둔 스냅샷의 검증이 전부 실패한다")
                .isEqualTo(contract.get("checksum").asText());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "policy-snapshot-response.json",
            "policy-snapshot-with-exclusion.json"
    })
    @DisplayName("계약이 요구하는 필드가 모두, 같은 값으로 있다")
    void shouldSatisfyContractShape(String contractFile) throws Exception {
        JsonNode contract = readContract(contractFile);

        // 체크섬을 계산한 바로 그 바이트를 검증한다. 별도 매퍼로 다시 직렬화하면
        // 설정 차이 때문에 "체크섬은 맞는데 모양은 다른" 상태를 놓칠 수 있다.
        ObjectNode actual = (ObjectNode) MAPPER.readTree(
                CANONICAL.of(renderSnapshot(contract.get("$given").asText())));
        actual.put("checksum", contract.get("checksum").asText());

        List<String> mismatches = new ArrayList<>();
        compare("", contract, actual, mismatches);

        assertThat(mismatches)
                .as("claims가 기대하는 형태를 만족하지 않는다. "
                        + "필드 추가는 자유지만 삭제·의미 변경은 새 버전(/api/v2)이다")
                .isEmpty();
    }

    /**
     * ★ 최소권한 — 계약이 <b>금지한</b> 필드가 응답 어디에도 없어야 한다.
     *
     * <p>다른 검증은 "있어야 할 것이 있는가"를 보지만, 이것은 반대다.
     * 필드는 실수로 늘어나기 쉽고, 늘어나도 소비자는 아무 불편을 못 느끼므로
     * 아무도 알아채지 못한다. 개인정보는 그렇게 샌다.
     */
    @Test
    @DisplayName("★ 계약이 금지한 필드가 응답 어디에도 없다")
    void shouldNotExposeForbiddenFields() throws Exception {
        JsonNode forbidden = readContract("policy-snapshot-forbidden-fields.json")
                .get("forbiddenFieldNames");

        JsonNode actual = MAPPER.readTree(CANONICAL.of(renderSnapshot("부담보계약")));
        List<String> present = new ArrayList<>();
        collectFieldNames(actual, present);

        for (JsonNode name : forbidden) {
            assertThat(present)
                    .as("%s 는 심사에 불필요하다. 계약 위반이자 개인정보 과다 제공이다",
                            name.asText())
                    .doesNotContain(name.asText());
        }
    }

    /**
     * ★★ 체크섬 알고리즘 합의 — 두 레포가 같은 바이트열에 고정된다.
     *
     * <p>위의 다른 검증들은 <b>순환</b>의 위험이 있다. 기대값이 이 저장소의 렌더 결과에서
     * 나왔다면, 그것은 "오늘의 동작이 바뀌지 않았다"(드리프트 감지)일 뿐
     * "claims와 합의했다"가 아니다.
     *
     * <p>그래서 계약 파일에 <b>정규화된 바이트열 자체</b>를 박아둔다.
     * <ul>
     *   <li>여기(BS): 렌더 결과가 정확히 그 바이트인지 확인한다</li>
     *   <li>claims: 그 바이트를 자기 알고리즘으로 해싱해 같은 체크섬이 나오는지 확인한다</li>
     * </ul>
     * 둘 다 통과해야 "claims가 저장한 스냅샷을 나중에 검증할 수 있다"가 성립한다.
     */
    @Test
    @DisplayName("★★ 정규화 바이트열이 계약과 정확히 일치한다 — 알고리즘 합의")
    void shouldRenderExactCanonicalBytes() throws Exception {
        JsonNode contract = readContract("policy-snapshot-canonical-body.json");

        String canonical = CANONICAL.of(renderSnapshot("부담보계약"));

        assertThat(canonical)
                .as("정규화 규칙(키 정렬·공백·null 포함)이 한쪽에서만 바뀌면 여기서 깨진다")
                .isEqualTo(contract.get("canonicalBody").asText());
        assertThat(SnapshotChecksum.of(canonical).value())
                .isEqualTo(contract.get("checksum").asText());
    }

    @Test
    @DisplayName("같은 입력은 항상 같은 체크섬 — 두 번 렌더해도 바이트가 같다")
    void shouldBeDeterministic() {
        String first = CANONICAL.of(renderSnapshot("부담보계약"));
        String second = CANONICAL.of(renderSnapshot("부담보계약"));

        assertThat(first).isEqualTo(second);
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────

    /** 계약 파일의 {@code $given}이 어느 픽스처를 쓸지 지정한다. */
    private PolicySnapshotResponse renderSnapshot(String given) {
        Policy policy = switch (given) {
            case "표준계약" -> 표준계약();
            case "부담보계약" -> 부담보계약();
            default -> throw new IllegalArgumentException(
                    "계약 파일이 모르는 픽스처를 지정했습니다: " + given);
        };
        return PolicySnapshotResponse.from(policy.snapshotAsOf(사고일, 청구접수));
    }

    private JsonNode readContract(String fileName) throws Exception {
        String path = "/contracts/" + fileName;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "계약 파일이 없습니다: %s. claims 레포에서 동기화하세요.".formatted(path));
            }
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 계약에 적힌 것만 비교한다.
     *
     * <p>응답에만 있는 필드는 위반이 아니다 — <b>필드 추가는 하위 호환</b>이기 때문이다.
     * 반대로 계약에 있는데 응답에 없거나 값이 다르면 위반이다.
     * {@code $}로 시작하는 키는 계약 파일의 메타데이터이므로 건너뛴다.
     */
    private void compare(String path, JsonNode expected, JsonNode actual,
                         List<String> mismatches) {
        if (expected.isObject()) {
            Iterator<String> names = expected.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (name.startsWith("$")) {
                    continue;
                }
                JsonNode child = actual.get(name);
                if (child == null) {
                    mismatches.add("%s/%s 없음".formatted(path, name));
                    continue;
                }
                compare(path + "/" + name, expected.get(name), child, mismatches);
            }
            return;
        }

        if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) {
                mismatches.add("%s 배열 크기 불일치: 기대 %d, 실제 %s"
                        .formatted(path, expected.size(),
                                actual.isArray() ? actual.size() : "배열 아님"));
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                compare(path + "[" + i + "]", expected.get(i), actual.get(i), mismatches);
            }
            return;
        }

        if (!expected.equals(actual)) {
            mismatches.add("%s 값 불일치: 기대 %s, 실제 %s".formatted(path, expected, actual));
        }
    }

    /** 중첩 객체·배열을 모두 훑어 필드명을 모은다. */
    private void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                names.add(name);
                collectFieldNames(node.get(name), names);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, names));
        }
    }
}
