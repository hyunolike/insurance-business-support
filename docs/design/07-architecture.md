# 07. 아키텍처 — business-support

> 아키텍처 원칙·강제 장치·CI 구성은 **claims와 동일하게** 간다.
> 두 레포가 같은 규약을 쓰는 것이 유지보수에 중요하다.
> 상세 근거: `insurance-claims-platform/docs/design/07-architecture.md`
> 이 문서는 BS 고유의 구조와 추가 규칙만 다룬다.

---

## 1. 모듈 구조

```
insurance-business-support/
├── policy-domain/               ← 순수 자바. 의존성 0
├── policy-application/          ← 유스케이스, 포트 정의
├── policy-underwriting/         ← 언더라이팅 룰 엔진
├── policy-adapter-web/          ← REST (스냅샷 API 포함)
├── policy-adapter-persistence/  ← JPA, Flyway, Bitemporal 저장소
├── policy-adapter-messaging/    ← Kafka, Outbox 릴레이
├── policy-adapter-external/     ← 건강진단·적부조사·신용정보 (스텁)
└── policy-bootstrap/            ← Spring Boot 앱
```

### 1.1 모듈별 허용 의존성

| 모듈 | 허용 | **금지** |
|---|---|---|
| `policy-domain` | JDK만 (+ Lombok compileOnly) | **Spring 전부, JPA, Jackson** |
| `policy-underwriting` | `policy-domain` | Spring, JPA |
| `policy-application` | `policy-domain`, `policy-underwriting`, `spring-tx`, `spring-context`(DI) | **JPA, 웹, Kafka, `ApplicationEventPublisher`** |
| `policy-adapter-*` | `policy-application` + 각자 기술 | 다른 어댑터 |
| `policy-bootstrap` | 전부 | — |

```groovy
// policy-domain/build.gradle
dependencies {
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core'
    // Spring 없음. JPA 없음.
}
```

---

## 2. 패키지 구조

```
policy-domain/.../domain/
├── shared/
│   ├── AggregateRoot.java          # pullEvents()
│   ├── DomainEvent.java
│   ├── Temporal.java               # ★ valid/recorded 시간축 인터페이스
│   └── vo/  Money · InsuredRef · EncryptedText · KcdRange
├── application/                    # 청약 (용어 충돌 주의: 계층 아님)
│   ├── Application.java · ApplicationStatus.java
│   ├── DisclosureItem.java
│   └── event/
├── underwriting/
│   ├── UnderwritingCase.java · UnderwritingDecision.java
│   ├── ProposedExclusion.java · UwRuleTrace.java
│   ├── ConsentRecord.java
│   └── event/
├── policy/
│   ├── Policy.java                 # ★ 애그리거트 루트
│   ├── PolicyVersion.java · Coverage.java · Exclusion.java
│   ├── PolicyStatus.java · PolicyTransitions.java
│   ├── PolicySnapshot.java · SnapshotChecksum.java
│   └── event/  PolicyIssued · PolicyCorrected · PolicyLapsed ...
├── premium/
│   ├── PremiumAccount.java · PremiumPayment.java · DemandNotice.java
└── port/
    ├── PolicyNumberGenerator.java
    ├── ProductCatalogPort.java
    ├── PremiumCalculationPort.java
    ├── MedicalExamPort.java
    ├── FieldInvestigationPort.java
    └── NotificationPort.java
```

> ⚠️ **용어 충돌 주의**: `domain/application/`은 **청약(Application)** 도메인이지
> 애플리케이션 계층이 아니다. 헷갈리기 쉬우므로 패키지 주석에 명시한다.
> 대안으로 `domain/proposal/`을 쓸 수 있으나, 업무 용어가 "청약"이므로
> **유비쿼터스 언어를 우선**한다.

---

## 3. Bitemporal 구현

이 저장소 고유의 설계 난제다.

### 3.1 도메인 계층

```java
public interface Temporal {
    LocalDate validFrom();
    LocalDate validTo();
    Instant   recordedAt();
    Instant   supersededAt();      // null = 현행

    default boolean isEffectiveOn(LocalDate asOf, Instant knownAt) {
        return !asOf.isBefore(validFrom()) && asOf.isBefore(validTo())
            && !knownAt.isBefore(recordedAt())
            && (supersededAt() == null || knownAt.isBefore(supersededAt()));
    }
}
```

```java
public class Policy extends AggregateRoot {
    public PolicySnapshot snapshotAsOf(LocalDate asOf, Instant knownAt) {
        PolicyStatus status = versions.stream()
            .filter(v -> v.isEffectiveOn(asOf, knownAt))
            .findFirst()
            .map(PolicyVersion::status)
            .orElseThrow(() -> new SnapshotNotAvailableException(policyNo, asOf));

        return PolicySnapshot.of(
            policyNo, asOf, knownAt, status,
            coverages.stream().filter(c -> c.isEffectiveOn(asOf, knownAt)).toList(),
            exclusions.stream().filter(e -> e.isEffectiveOn(asOf, knownAt)).toList(),
            versionNumberAt(asOf, knownAt)
        );
    }
}
```

**시점 조회 로직이 도메인에 있다.** 순수 함수라 DB 없이 단위 테스트가 가능하다.

### 3.2 영속성 계층

전체 이력을 메모리에 올리는 것은 비현실적이므로, 리포지토리가 **필요한 시점의 행만** 읽어 애그리거트를 조립한다.

```java
public interface PolicyRepository {
    Optional<Policy> findCurrent(PolicyNo no);
    /** asOf/knownAt에 해당하는 행만 로드해 조립 — 스냅샷 전용 경로 */
    Optional<Policy> findAsOf(PolicyNo no, LocalDate asOf, Instant knownAt);
    Policy save(Policy policy);
}
```

### 3.3 절대 규칙

```java
// ❌ 절대 금지
entityManager.createQuery("UPDATE CoverageVersion SET insuredAmount = ...").executeUpdate();

// ✅ 변경 = 기존 구간 종료 + 새 행
// ✅ 정정 = supersededAt 설정(유일하게 허용되는 UPDATE) + 새 행
```

**DB 권한으로도 막는다.**

```sql
-- superseded_at 외의 컬럼 UPDATE를 트리거로 차단
CREATE OR REPLACE FUNCTION reject_history_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF (to_jsonb(NEW) - 'superseded_at') IS DISTINCT FROM (to_jsonb(OLD) - 'superseded_at') THEN
        RAISE EXCEPTION '이력 레코드는 superseded_at 외에는 수정할 수 없습니다 (id=%)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER coverage_version_immutable
    BEFORE UPDATE ON coverage_version
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
-- exclusion_version, policy_version 에도 동일 적용

REVOKE DELETE ON coverage_version, exclusion_version, policy_version FROM policy_app;
```

---

## 4. 아키텍처 강제 장치

### 4.1 ArchUnit

```java
@AnalyzeClasses(packages = "com.insurance.policy")
class ArchitectureTest {

    @ArchTest
    static final ArchRule 도메인은_스프링을_모른다 =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                                "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule 애플리케이션은_스프링_이벤트퍼블리셔를_쓰지_않는다 =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher");

    @ArchTest  // ★ BS 고유 규칙
    static final ArchRule 이력_엔티티에_세터가_없다 =
        noClasses().that().implement(Temporal.class)
            .should().haveMethodsThat(haveNameStartingWith("set"))
            .because("Bitemporal 이력은 수정하지 않는다. 변경은 새 행으로 표현한다.");

    @ArchTest  // ★ BS 고유 규칙
    static final ArchRule 고지사항은_평문_필드로_보관하지_않는다 =
        classes().that().haveSimpleName("DisclosureItem")
            .should().haveOnlyFieldsThat(
                fieldTypeIsNot(String.class).or(fieldNameIsNot("detail")))
            .because("고지 상세는 민감 건강정보다. EncryptedText 타입만 허용");

    @ArchTest
    static final ArchRule 모든_포트는_구현체가_있다 =
        classes().that().resideInAPackage("..domain.port..").and().areInterfaces()
            .should(haveAtLeastOneImplementationInProduction());

    @ArchTest
    static final ArchRule 금액은_Money로만 =
        noClasses().that().resideInAPackage("..domain..")
            .should().haveFieldsOfType(BigDecimal.class);
}
```

### 4.2 CI

claims와 동일한 구성에 **계약 테스트가 추가**된다.

```yaml
name: CI
on:
  pull_request: { branches: [develop, main] }
  push:         { branches: [develop, main] }

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: gradle }

      - name: Build & Test (Testcontainers)
        run: ./gradlew clean build

      - name: Architecture rules
        run: ./gradlew test --tests '*ArchitectureTest'

      - name: Coverage gate
        run: ./gradlew jacocoTestCoverageVerification

      - name: Underwriting golden cases
        run: ./gradlew :policy-underwriting:test --tests '*GoldenCaseTest'

      - name: Bitemporal invariants
        run: ./gradlew test --tests '*BitemporalTest'

      - name: ★ Contract test (claims가 정의한 계약)
        run: ./gradlew contractTest

      - name: OpenAPI drift check
        run: |
          ./gradlew generateOpenApiDocs
          git diff --exit-code docs/design/openapi.yaml \
            || (echo "::error::OpenAPI 스펙이 코드와 다릅니다."; exit 1)

      - name: Secret scanning
        uses: gitleaks/gitleaks-action@v2
```

> **`contractTest`가 이 저장소의 특별한 책임이다.**
> BS는 업스트림이므로, 계약을 깨면 claims가 배포 후에 깨지는 게 아니라 **여기 PR에서 잡혀야 한다.**

**커버리지 임계**

```groovy
jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = 'PACKAGE'
            includes = ['com.insurance.policy.domain.*', 'com.insurance.policy.underwriting.*']
            limit { counter = 'BRANCH'; minimum = 0.85 }
        }
        rule {
            limit { counter = 'LINE';   minimum = 0.75 }
            limit { counter = 'BRANCH'; minimum = 0.65 }
        }
    }
}
check.dependsOn jacocoTestCoverageVerification
```

---

## 5. 테스트 전략

### 5.1 반드시 테스트해야 하는 시나리오

```
[Bitemporal — 이 저장소의 핵심]
  □ 같은 asOf를 다른 knownAt으로 조회하면 다른 결과가 나온다
  □ 정정 후에도 정정 전 시점(knownAt)의 답이 재현된다
  □ 변경(Endorsement)은 과거 스냅샷을 바꾸지 않는다
  □ 정정(Correction)은 과거 스냅샷을 바꾸고 policy.corrected를 발행한다
  □ 이력 레코드 UPDATE 시도 시 DB 트리거가 거부한다
  □ 유효기간이 겹치는 담보 INSERT 시 EXCLUDE 제약이 거부한다
  □ 계약 생성 이전 asOf 조회 → 404

[스냅샷 API]
  □ 같은 (policyNo, asOf, knownAt)은 항상 같은 응답 (100회 반복 검증)
  □ checksum이 응답 본문과 일치하고, claims와 같은 알고리즘이다
  □ 고지사항·주소·모집인 정보가 응답에 없다 (스키마 네거티브 테스트)
  □ insuredRef 불일치 시 404 (403 아님 — 존재를 노출하지 않음)
  □ p99 < 300ms

[언더라이팅]
  □ 골든 케이스 전건 (03-underwriting.md §4 예시 1~3)
  □ 부담보 제안이 계약의 Exclusion으로 정확히 변환된다
  □ 동의 기록 없이 부담보 계약 성립 시도 → 409
  □ 자동 제안과 다른 결정 시 overrideReason 없으면 400

[계약 보전]
  □ 최고 발송 기록 없이 실효 전환 시도 → 거부
  □ 부활 시 면책기간이 재기산된다
  □ 부활 시 재고지 내용으로 재심사가 수행된다
  □ 상태 전이 위반 → 409 (500 아님)

[레포 간 E2E — Phase 5]
  □ 부담보 인수 계약 → claims에서 해당 KCD 청구 → D-POL-004 부지급
  □ policy.corrected 발행 → claims가 재심사 대상으로 표시
```

### 5.2 계약 테스트

```java
@Tag("contract")
class PolicySnapshotContractTest extends IntegrationTestBase {

    @ParameterizedTest
    @MethodSource("fixtures")   // src/test/resources/contracts/*.json
    void claims가_정의한_계약을_만족한다(ContractFixture f) {
        seedPolicy(f.given());
        var response = mockMvc.perform(get(f.request())).andReturn();
        assertThatJson(response).conformsTo(f.expectedSchema());
    }

    @Test
    void checksum_알고리즘이_claims와_동일하다() {
        var snapshot = fetchSnapshot("P2026-0001234", LocalDate.of(2026, 3, 14));
        assertThat(snapshot.checksum())
            .isEqualTo(ClaimsChecksumReference.compute(snapshot.bodyWithoutChecksum()));
    }
}
```

---

## 6. 기술 스택

claims와 동일하다. 두 레포의 스택을 일치시켜 운영 부담을 줄인다.

| 영역 | 선택 |
|---|---|
| 언어 | Java 21 (LTS) |
| 프레임워크 | Spring Boot 3.x (도메인 모듈 제외) |
| 빌드 | Gradle 멀티모듈 |
| DB | **PostgreSQL 15** — `btree_gist`, `daterange`, `EXCLUDE` 제약 필수 |
| 마이그레이션 | Flyway |
| 메시징 | Kafka (Outbox 경유) |
| 캐시 | Redis (스냅샷 캐시) |
| 테스트 | JUnit 5, AssertJ, Testcontainers, ArchUnit |

> **PostgreSQL 의존도가 claims보다 높다.** `EXCLUDE USING gist`는 다른 DB에 이식할 수 없다.
> 이식성보다 **데이터 정확성**을 택한 의도적 결정이다. 계약 이력의 모순은 치명적이다.

---

## 7. 설정

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/policy}
    username: ${DB_USERNAME:policy}
    password: ${DB_PASSWORD}              # 기본값 없음 → 미설정 시 기동 실패
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  data:
    redis:
      host: ${REDIS_HOST:localhost}

policy:
  encryption:
    key: ${POLICY_ENCRYPTION_KEY}         # 기본값 없음
    key-version: ${POLICY_ENCRYPTION_KEY_VERSION:v1}
  underwriting:
    ruleset-version: ${UW_RULESET_VERSION:2026.01}
    max-auto-exclusions: ${UW_MAX_AUTO_EXCLUSIONS:2}
  snapshot:
    cache-past-ttl: ${SNAPSHOT_CACHE_PAST_TTL:P365D}    # 과거는 사실상 영구
    cache-current-ttl: ${SNAPSHOT_CACHE_CURRENT_TTL:PT5M}
  premium:
    grace-period: ${PREMIUM_GRACE_PERIOD:P2M}
    demand-notice-period: ${DEMAND_NOTICE_PERIOD:P14D}
  reinstatement:
    window: ${REINSTATEMENT_WINDOW:P3Y}
```

---

## 8. 보안

| 항목 | 방식 |
|---|---|
| 스냅샷 API | **서비스 토큰 필수.** 고객 토큰으로 접근 불가 |
| 고지사항 조회 | `ROLE_UNDERWRITER` + **접근 시마다 `audit_log` 기록** |
| 정정 API | `ROLE_UW_MANAGER` + 승인자 기록 필수 |
| 계약 조회 (고객) | 본인 계약만 |
| 저장 | 민감 컬럼 AES-GCM |
| 로깅 | 타입 수준 마스킹 (`EncryptedText.toString()` → `"***"`) |

### 8.1 스냅샷 API 접근 통제

```java
@PreAuthorize("hasAuthority('SCOPE_policy.snapshot.read')")
@GetMapping("/policies/{policyNo}/snapshot")
public PolicySnapshotResponse snapshot(...) {
    auditLog.record(SNAPSHOT_FETCH, policyNo, asOf);   // 모든 조회 기록
    ...
}
```

claims 외의 소비자가 붙을 수 있으므로 **스코프 기반 인가**를 쓴다.

---

## 9. 비기능 요구사항

| 항목 | 목표 | 측정 |
|---|---|---|
| 스냅샷 API p99 | **< 300ms** | `policy.snapshot.duration` |
| 스냅샷 오류율 | < 0.1% | — |
| 청약~자동결정 | p99 < 3s | `uw.duration` |
| 시점 재현성 | **100%** | 회귀 테스트 |
| 가용성 | 99.9% | — |

> **시점 재현성 100%가 절대 요구사항이다.** 이것이 깨지면 claims의 심사 근거가 무너지고,
> 두 레포를 나눈 설계 전체가 의미를 잃는다.

---

## 10. 로컬 환경

claims와 동일한 `compose.yaml` 구성 (KRaft Kafka, healthcheck).
**포트만 다르게 둔다** — 두 서비스를 동시에 띄워 레포 간 E2E를 돌려야 하기 때문이다.

| 서비스 | claims | business-support |
|---|---|---|
| 애플리케이션 | 8080 | **8081** |
| PostgreSQL | 5432 | **5433** |
| Redis | 6379 | **6380** |
| Kafka | 9092 (공유) | 9092 (공유) |

Kafka는 **공유한다.** 두 서비스가 실제로 이벤트를 주고받아야 한다.

---

## 다음 문서

- [`08-roadmap.md`](08-roadmap.md) — 구현 계획
- claims: `docs/design/07-architecture.md` (아키텍처 원칙 정본)
