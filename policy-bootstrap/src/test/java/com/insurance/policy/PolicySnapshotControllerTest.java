package com.insurance.policy;

import static com.insurance.policy.domain.policy.PolicyFixtures.POLICY_NO;
import static com.insurance.policy.domain.policy.PolicyFixtures.insured;
import static com.insurance.policy.domain.policy.PolicyFixtures.사고일;
import static com.insurance.policy.domain.policy.PolicyFixtures.부담보계약;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insurance.policy.adapter.web.snapshot.CachedSnapshotRenderer;
import com.insurance.policy.adapter.web.snapshot.CanonicalJson;
import com.insurance.policy.adapter.web.snapshot.PolicySnapshotController;
import com.insurance.policy.application.policy.PolicySnapshotService;
import com.insurance.policy.config.SecurityConfig;
import com.insurance.policy.config.TimeConfig;
import com.insurance.policy.domain.policy.PolicySnapshot;
import com.insurance.policy.domain.policy.exception.PolicyNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ★ 스냅샷 API — 이 저장소에서 가장 중요한 엔드포인트.
 *
 * <p><b>왜 이 테스트가 뒤늦게 생겼는가.</b> 웹 테스트가 하나도 없어서,
 * {@code policy-adapter-web}이 {@code -parameters} 없이 컴파일되는 바람에
 * <b>이 엔드포인트가 런타임에 전부 400을 돌려주고 있었다는 사실을 아무도 몰랐다.</b>
 * 통합 테스트는 서비스 계층까지만 봤고, 컨트롤러의 파라미터 바인딩은 아무도 실행하지 않았다.
 *
 * <p>그래서 여기서는 <b>HTTP 경계</b>를 본다 — 경로 변수와 쿼리 파라미터가 실제로 바인딩되는가,
 * 스코프 인가가 걸리는가, 그리고 응답에 민감정보가 새지 않는가.
 * DB 왕복은 {@code PolicyBitemporalIntegrationTest}가 본다.
 */
@WebMvcTest(controllers = PolicySnapshotController.class)
@Import({SecurityConfig.class, TimeConfig.class, CanonicalJson.class,
        CachedSnapshotRenderer.class})
@DisplayName("계약 스냅샷 API")
class PolicySnapshotControllerTest {

    private static final String SCOPE = "SCOPE_policy.snapshot.read";
    private static final String INSURED_REF = insured().insuredRef().value();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PolicySnapshotService snapshotService;

    private PolicySnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = 부담보계약().snapshotAsOf(사고일, Instant.parse("2026-04-02T01:15:00Z"));
    }

    @Test
    @DisplayName("★ 경로 변수와 쿼리 파라미터가 실제로 바인딩된다")
    void shouldBindPathAndQueryParameters() throws Exception {
        when(snapshotService.snapshot(any(), any(), any(), any())).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", INSURED_REF)
                        .with(caller(SCOPE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNo").value(POLICY_NO.value()))
                .andExpect(jsonPath("$.asOf").value(사고일.toString()));

        // 파라미터가 컨트롤러까지 값으로 도착했는지 확인한다.
        // -parameters 가 빠지면 여기까지 오지도 못하고 400 이 된다.
        verify(snapshotService).snapshot(eq(POLICY_NO), eq(insured().insuredRef()),
                eq(사고일), eq(null));
    }

    @Test
    @DisplayName("knownAt을 넘기면 그 시점의 진실로 조회한다")
    void shouldPassKnownAt() throws Exception {
        Instant 정정직전 = Instant.parse("2026-05-01T00:00:00Z");
        when(snapshotService.snapshot(any(), any(), any(), any())).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", INSURED_REF)
                        .param("knownAt", "2026-05-01T00:00:00Z")
                        .with(caller(SCOPE)))
                .andExpect(status().isOk());

        verify(snapshotService).snapshot(eq(POLICY_NO), eq(insured().insuredRef()),
                eq(사고일), eq(정정직전));
    }

    @Test
    @DisplayName("체크섬이 채워진다 — claims가 복제본 무결성을 검증하는 값")
    void shouldFillChecksum() throws Exception {
        when(snapshotService.snapshot(any(), any(), any(), any())).thenReturn(snapshot);

        String body = mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", INSURED_REF)
                        .with(caller(SCOPE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"checksum\":\"sha256:");
    }

    @Test
    @DisplayName("★ 같은 입력은 항상 같은 체크섬을 준다 — claims의 재현성이 여기 달려 있다")
    void shouldProduceStableChecksum() throws Exception {
        when(snapshotService.snapshot(any(), any(), any(), any())).thenReturn(snapshot);

        String first = performSnapshot();
        String second = performSnapshot();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("★ 최소권한 — 응답에 고지사항·주소·연락처·모집인·보험료가 없다")
    void shouldNotLeakSensitiveFields() throws Exception {
        when(snapshotService.snapshot(any(), any(), any(), any())).thenReturn(snapshot);

        String body = performSnapshot();

        assertThat(body)
                .as("claims가 심사에 필요한 것은 고지 내용이 아니라 그 결과물인 부담보 조건이다")
                .doesNotContain("disclosure", "notification", "address", "phone",
                        "agent", "premium", "residentRegistrationNumber", "holderName",
                        "insuredName", "birthDate");

        // 피보험자 블록은 세 필드만 갖는다. 성명·생년월일·연락처가 새로 붙으면 여기서 깨진다.
        // "name" 을 문자열로 찾는 것으로는 부족하다 — productName 처럼 정당한 필드도 걸린다.
        var insuredNode = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("insured");
        assertThat(insuredNode.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("insuredRef", "birthYear", "relationToHolder");

        // 생년'월일'이 아니라 생년만. 전체 생년월일은 심사에 불필요하다.
        assertThat(insuredNode.get("birthYear").asInt()).isBetween(1900, 2100);
    }

    @Test
    @DisplayName("스코프가 없으면 거부한다 — 고객 토큰으로는 접근할 수 없다")
    void shouldRejectWithoutScope() throws Exception {
        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", INSURED_REF)
                        .with(caller("SCOPE_policy.read")))
                .andExpect(status().isForbidden());

        verify(snapshotService, never()).snapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("인증이 없으면 401이다")
    void shouldRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", INSURED_REF))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("asOf가 없으면 400이다 — 시점 없는 스냅샷은 의미가 없다")
    void shouldRequireAsOf() throws Exception {
        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("insuredRef", INSURED_REF)
                        .with(caller(SCOPE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("피보험자가 다르면 403이 아니라 404다 — 계약의 존재를 노출하지 않는다")
    void shouldReturnNotFoundOnInsuredMismatch() throws Exception {
        when(snapshotService.snapshot(any(), any(), any(), any()))
                .thenThrow(new PolicyNotFoundException(POLICY_NO));

        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", "CI-someone-else")
                        .with(caller(SCOPE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));
    }

    @Test
    @DisplayName("미래의 asOf는 400이다")
    void shouldRejectFutureAsOf() throws Exception {
        LocalDate 미래 = LocalDate.now().plusYears(1);
        when(snapshotService.snapshot(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("기준일(asOf)은 미래일 수 없습니다"));

        mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 미래.toString())
                        .param("insuredRef", INSURED_REF)
                        .with(caller(SCOPE)))
                .andExpect(status().isBadRequest());
    }

    /** 스코프 하나를 가진 서비스 호출자. SecurityConfig 가 httpBasic 이라 jwt() 는 쓸 수 없다. */
    private static RequestPostProcessor caller(String authority) {
        return SecurityMockMvcRequestPostProcessors.user("claims-service")
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private String performSnapshot() throws Exception {
        return mockMvc.perform(get("/api/v1/policies/{no}/snapshot", POLICY_NO.value())
                        .param("asOf", 사고일.toString())
                        .param("insuredRef", INSURED_REF)
                        .with(caller(SCOPE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
