package com.insurance.policy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 설정 (Phase 0 골격).
 *
 * <p>기본은 전면 차단이다. 나중에 "보안을 붙이는" 일은 늘 미뤄지므로 Phase 0부터 건다.
 *
 * <p>Phase 1에서 추가할 것:
 * <ul>
 *   <li>계약 스냅샷 API는 <b>서비스 토큰 전용</b>. 고객 토큰으로는 접근할 수 없다.
 *       claims 외의 소비자가 붙을 수 있으므로 스코프 기반 인가를 쓴다
 *       ({@code SCOPE_policy.snapshot.read}).</li>
 *   <li>고지사항 조회는 {@code ROLE_UNDERWRITER} + 접근 시마다 audit_log 기록.
 *       민감 건강정보라 "누가 언제 봤는가"가 감사 대상이다.</li>
 *   <li>소급 정정 API는 {@code ROLE_UW_MANAGER} + 승인자 기록 필수.
 *       과거 사실을 바꾸는 행위이므로 단독 실행을 막는다.</li>
 * </ul>
 *
 * @see docs/design/07-architecture.md §8
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // REST API는 상태를 세션에 두지 않는다.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 세션이 없으므로 CSRF 토큰도 의미가 없다.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 컨테이너 오케스트레이터가 인증 없이 볼 수 있어야 한다.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // 지표는 내부망에서만. 운영 배포 시 네트워크 정책으로 한 번 더 막는다.
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_metrics.read")
                        // 나머지는 전부 차단이 기본값이다.
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
