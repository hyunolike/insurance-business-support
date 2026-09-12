package com.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.policy.adapter.web.command.PolicyChangeController;
import com.insurance.policy.application.policy.PolicyCommandService;
import com.insurance.policy.config.SecurityConfig;
import com.insurance.policy.config.TimeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 변경·정정 엔드포인트.
 *
 * <p>Docker 없이 도는 웹 슬라이스 테스트다. DB 왕복은
 * {@code PolicyBitemporalIntegrationTest}가 보고, 여기서는 <b>경계</b>만 본다 —
 * 권한, 검증, 그리고 무엇보다 <b>행위자 식별자가 본문이 아니라 인증 주체에서 온다</b>는 것.
 *
 * <p>마지막 항목이 핵심이다. 본문으로 받으면 호출자가 다른 사람 이름으로
 * 변경 이력을 남기거나, 혼자서 요청자와 승인자를 모두 적어 4-eyes를 무력화할 수 있다.
 */
@WebMvcTest(controllers = PolicyChangeController.class)
@Import({SecurityConfig.class, TimeConfig.class})
@DisplayName("계약 변경·정정 API")
class PolicyChangeControllerTest {

    private static final String POLICY_NO = "P2026-0001234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PolicyCommandService commandService;

    // ─── 요청 본문 ────────────────────────────────────────────────────────

    private String endorsementBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "coverageCode", "COV-INPT-COVERED",
                "newInsuredAmount", 30_000_000L,
                "newTerms", java.util.Map.of("coinsuranceRate", "0.20"),
                "effectiveFrom", "2026-06-01",
                "reason", "가입금액 증액"));
    }

    private String correctionBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "exclusionId", "EXC-2026-000045",
                "reason", "착오 등록 확인",
                "requestedBy", "UW-0007"));
    }

    @Nested
    @DisplayName("변경 (Endorsement)")
    class Endorsement {

        @Test
        @WithMockUser(username = "UW-0007", roles = "UNDERWRITER")
        @DisplayName("★ actorRef는 본문이 아니라 인증 주체에서 온다")
        void shouldTakeActorFromPrincipal() throws Exception {
            doNothing().when(commandService).endorse(any());

            mockMvc.perform(post("/api/v1/policies/{no}/endorsements", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(endorsementBody()))
                    .andExpect(status().isAccepted());

            var captor = ArgumentCaptor.forClass(
                    PolicyCommandService.EndorseCoverageCommand.class);
            verify(commandService).endorse(captor.capture());

            assertThat(captor.getValue().actorRef())
                    .as("본문에 actorRef가 없으므로 사칭할 수 없다")
                    .isEqualTo("UW-0007");
            assertThat(captor.getValue().policyNo().value()).isEqualTo(POLICY_NO);
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        @DisplayName("언더라이터가 아니면 거부한다")
        void shouldRejectWithoutRole() throws Exception {
            mockMvc.perform(post("/api/v1/policies/{no}/endorsements", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(endorsementBody()))
                    .andExpect(status().isForbidden());

            verify(commandService, never()).endorse(any());
        }

        @Test
        @WithMockUser(username = "UW-0007", roles = "UNDERWRITER")
        @DisplayName("사유 없는 변경은 400이다 — 이력에 남을 근거가 없다")
        void shouldRejectWithoutReason() throws Exception {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "coverageCode", "COV-INPT-COVERED",
                    "newInsuredAmount", 30_000_000L,
                    "newTerms", java.util.Map.of("coinsuranceRate", "0.20"),
                    "effectiveFrom", "2026-06-01"));

            mockMvc.perform(post("/api/v1/policies/{no}/endorsements", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(commandService, never()).endorse(any());
        }

        @Test
        @WithMockUser(username = "UW-0007", roles = "UNDERWRITER")
        @DisplayName("자기부담률이 1을 넘으면 400이다")
        void shouldRejectImpossibleCoinsuranceRate() throws Exception {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "coverageCode", "COV-INPT-COVERED",
                    "newInsuredAmount", 30_000_000L,
                    "newTerms", java.util.Map.of("coinsuranceRate", "1.50"),
                    "effectiveFrom", "2026-06-01",
                    "reason", "오타"));

            mockMvc.perform(post("/api/v1/policies/{no}/endorsements", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(commandService, never()).endorse(any());
        }
    }

    @Nested
    @DisplayName("정정 (Correction)")
    class Correction {

        @Test
        @WithMockUser(username = "UW-MGR-01", roles = "UW_MANAGER")
        @DisplayName("★ approvedBy는 본문이 아니라 인증 주체 — 혼자 4-eyes를 만들 수 없다")
        void shouldTakeApproverFromPrincipal() throws Exception {
            doNothing().when(commandService).correctExclusion(any());

            mockMvc.perform(post("/api/v1/policies/{no}/corrections", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(correctionBody()))
                    .andExpect(status().isAccepted());

            var captor = ArgumentCaptor.forClass(
                    PolicyCommandService.CorrectExclusionCommand.class);
            verify(commandService).correctExclusion(captor.capture());

            assertThat(captor.getValue().requestedBy())
                    .as("요청자는 본문에서")
                    .isEqualTo("UW-0007");
            assertThat(captor.getValue().approvedBy())
                    .as("승인자는 인증 주체에서. 본문으로 받으면 한 사람이 둘 다 적을 수 있다")
                    .isEqualTo("UW-MGR-01");
            assertThat(captor.getValue().replacement())
                    .as("replacement가 없으면 삭제 정정이다")
                    .isNull();
        }

        @Test
        @WithMockUser(username = "UW-0007", roles = "UNDERWRITER")
        @DisplayName("★ 언더라이터 권한만으로는 과거를 바꿀 수 없다")
        void shouldRequireManagerRole() throws Exception {
            mockMvc.perform(post("/api/v1/policies/{no}/corrections", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(correctionBody()))
                    .andExpect(status().isForbidden());

            verify(commandService, never()).correctExclusion(any());
        }

        @Test
        @WithMockUser(username = "UW-MGR-01", roles = "UW_MANAGER")
        @DisplayName("요청자 없는 정정은 400이다 — 누가 요청했는지 모르면 승인이 성립하지 않는다")
        void shouldRejectWithoutRequester() throws Exception {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "exclusionId", "EXC-2026-000045",
                    "reason", "착오 등록 확인"));

            mockMvc.perform(post("/api/v1/policies/{no}/corrections", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(commandService, never()).correctExclusion(any());
        }

        @Test
        @WithMockUser(username = "UW-MGR-01", roles = "UW_MANAGER")
        @DisplayName("KCD 범위 없는 교체는 400이다 — claims가 판정할 수 없다")
        void shouldRejectReplacementWithoutKcdRanges() throws Exception {
            String body = """
                    {
                      "exclusionId": "EXC-2026-000045",
                      "reason": "범위 정정",
                      "requestedBy": "UW-0007",
                      "replacement": {
                        "type": "BODY_PART",
                        "target": "척추",
                        "kcdRanges": [],
                        "validFrom": "2026-01-01",
                        "validTo": "2031-01-01",
                        "reason": "5년 내 수술 이력"
                      }
                    }
                    """;

            mockMvc.perform(post("/api/v1/policies/{no}/corrections", POLICY_NO)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(commandService, never()).correctExclusion(any());
        }
    }

    @Test
    @DisplayName("인증 없이는 아무것도 할 수 없다")
    void shouldRejectAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/policies/{no}/corrections", POLICY_NO)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody()))
                .andExpect(status().isUnauthorized());

        verify(commandService, never()).correctExclusion(any());
    }
}
