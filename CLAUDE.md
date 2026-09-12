# CLAUDE.md

Claude Code(claude.ai/code)가 이 저장소에서 작업할 때 참고하는 지침.

## 프로젝트

보험 계약·언더라이팅 시스템. 2개 저장소로 구성된 시스템의 **계약(Policy) 측**이며,
짝 저장소인 청구 시스템에 **계약 정보를 공급하는 원천**이다.

- 짝 저장소: `hyunolike/insurance-claims-platform` (실손의료보험 청구 심사)
- 스택: Java 21 LTS, Spring Boot 3.x, PostgreSQL 15, Kafka, Redis
- 아키텍처: DDD + 헥사고날, Gradle 멀티모듈, **Bitemporal 이력**, Transactional Outbox
- **현재 상태: Phase 1 완료. claims 의 Phase 2(청구 접수 + 스냅샷 연동)를 시작할 수 있다**

## 작업 전 반드시 읽을 것

| 문서 | 언제 |
|---|---|
| `docs/design/00-domain-glossary.md` | **항상.** 특히 §3 Bitemporal |
| `docs/design/02-domain-model.md` | 계약 변경·정정 관련 작업 |
| `docs/design/03-underwriting.md` | 언더라이팅 룰 작업 |
| `docs/design/05-api.md` | 스냅샷 API 작업 |
| `docs/design/06-data-model.md` | 스키마 작업 |
| `docs/design/01-context-map.md` | claims와 연동되는 작업 |
| `docs/design/08-roadmap.md` | 지금 어느 Phase인지 |

## 이 저장소의 제1원칙

> **과거 어느 시점의 계약이든, 그때 그대로 재현할 수 있어야 한다.**

claims의 청구 심사가 사고일 시점의 계약을 보고, 그 판단을 나중에 설명해야 하기 때문이다.
이 원칙이 깨지면 두 레포를 나눈 설계 전체가 의미를 잃는다.

## 절대 규칙

1. **이력 테이블의 기존 행을 `UPDATE`하지 않는다.** 유일한 예외는 `superseded_at` 설정이다. DB 트리거가 막고 있으니 우회하지 말 것.
2. **변경(Endorsement)과 정정(Correction)을 혼동하지 않는다.**
   - 변경 = "오늘부터 바뀐다" → 과거 스냅샷 불변 → `policy.endorsed`
   - 정정 = "과거가 원래 그랬다" → 과거 스냅샷 변경 → **`policy.corrected`** (claims 재심사 유발)
3. **`policy-domain`에 Spring/JPA/Jackson을 넣지 않는다.** 클래스패스에 없다.
4. **`policy-application`에서 `ApplicationEventPublisher`를 쓰지 않는다.** 애그리거트가 `record()`, Outbox로 저장.
5. **`@Transactional` 안에서 외부로 이벤트를 발행하지 않는다.** Outbox INSERT만.
6. **스냅샷 API 응답에 고지사항·주소·연락처·모집인 정보·보험료 금액을 넣지 않는다.** 최소권한.
7. **고지사항 상세(`detail`)는 `EncryptedText`로만 다룬다.** 평문 `String` 필드 금지. 조회 시 `audit_log` 기록 필수.
8. **`uw_rule_trace`의 `input_json`에 질병명 원문을 넣지 않는다.** `riskCategory`/`severity`만.
9. **테스트에 H2를 쓰지 않는다.** Testcontainers PostgreSQL. 이 스키마는 H2에서 생성조차 안 된다 (`EXCLUDE USING gist`, `daterange`, `TEXT[]`, `JSONB`).
10. **금액은 `Money` VO로만.** 원 단위 정수. 도메인에 `BigDecimal` 필드 금지.
11. **동의 기록 없이 부담보·할증 계약을 성립시키지 않는다.** 불완전판매다.
12. **최고 발송 기록 없이 실효 전환하지 않는다.** 절차 없는 실효는 무효가 될 수 있다.
13. **claims를 동기 호출하지 않는다.** 단방향 의존. `claim.paid`는 통계 적재만 하고 계약을 바꾸지 않는다.
14. **비밀값에 기본값을 주지 않는다.** 미설정 시 기동 실패가 정상이다.

## 명령어

```bash
# 인프라 (포트가 claims와 다름: DB 5433, Redis 6380)
docker compose up -d
docker compose down -v

# 빌드·테스트
./gradlew clean build
./gradlew :policy-domain:test                          # 도메인 단위 (빠름, Spring 없음)
./gradlew :policy-domain:test --tests '*TemporalTest'  # 시점 재현성
./gradlew test --tests '*ArchitectureTest'             # 아키텍처 규칙
./gradlew jacocoTestCoverageVerification

# Phase 1부터
./gradlew contractTest                                 # ★ claims와의 계약
# Phase 5부터
./gradlew :policy-underwriting:test --tests '*GoldenCaseTest'
# Phase 6부터
./gradlew generateOpenApiDocs

# 실행 (8081)
./gradlew :policy-bootstrap:bootRun
```

## 기능 추가 순서

```
1. policy-domain        모델 + Temporal 구현 + 불변식 + 이벤트 record()  → 순수 단위 테스트
2. policy-domain/port   필요한 포트 정의
3. policy-underwriting  룰 추가 시 여기 + 골든 케이스
4. policy-application   유스케이스 + @Transactional 경계 + Outbox
5. policy-adapter-*     JPA/컨트롤러  → Testcontainers, MockMvc
6. docs                 설계와 달라졌으면 설계 문서를 먼저 고친다
```

## 시간축을 가진 엔티티를 추가할 때

```
□ Temporal 인터페이스 구현 (validFrom/validTo/recordedAt/supersededAt)
□ 세터 없음 (ArchUnit이 검사)
□ EXCLUDE USING gist 겹침 방지 제약 추가
□ 이력 불변 트리거 적용
□ DELETE 권한 회수
□ asOf/knownAt 조회 테스트 (정정 전/후 재현)
```

## 언더라이팅 룰을 추가·수정할 때

1. `docs/design/03-underwriting.md` 룰 카탈로그에 **먼저** 등록 (`U-{단계}-{번호}`)
2. 임계값·매핑은 코드가 아니라 `uw_exclusion_mapping` / `uw_eligibility_rule` 테이블에
3. `UwRuleTrace`에 입력·출력·근거를 남긴다 (질병명 원문 제외)
4. 골든 케이스 추가
5. **기존 골든 케이스가 깨지면 의도한 변경인지 확인**
6. 애매하면 거절이 아니라 **회부(`REFER`)** — 부당 거절은 영업 손실 + 민원

## 스냅샷 API를 건드릴 때 ⚠️

이 API는 **공표된 공용 계약(Open Host Service)**이다. claims의 심사 근거가 여기서 나온다.

```
□ 필드 추가만 하는가? (삭제·의미 변경은 새 버전 /api/v2)
□ contractTest가 통과하는가?
□ checksum 알고리즘이 claims와 동일한가?
□ 같은 (policyNo, asOf, knownAt) 반복 조회가 항상 같은가?
□ 민감정보가 새로 노출되지 않는가?
```

계약을 깨면 **이 저장소의 CI가 실패**한다. 이것이 정상 동작이다.

## 커밋·PR

```
<type>: <내용> #<이슈번호>
feat · fix · docs · refactor · test · chore
```

- 브랜치: `feat/#이슈`, `fix/#이슈`, `docs/#이슈`, `refactor/#이슈`
- `main`/`develop` 직접 푸시 금지
- PR 머지 전 CI 전체 통과 필수 (build, ArchUnit, Bitemporal, contractTest, 커버리지, OpenAPI drift)

## 현재 구현 상태

```
Phase 0  골격 (멀티모듈, ArchUnit, Testcontainers, CI, btree_gist)   ☑ 완료
Phase 1  계약 모델 + 스냅샷 API  ← ★ claims의 진행을 여는 열쇠        ☑ 완료
           ☑ Policy 애그리거트 + snapshotAsOf(asOf, knownAt)
           ☑ Bitemporal 스키마 (V3) + EXCLUDE 제약 + 이력 불변 트리거
           ☑ PolicyJdbcRepository (INSERT 전용, superseded_at만 예외)
           ☑ GET /policies/{no}/snapshot + 체크섬
           ☑ GET /policies/{no} · /history
           ☑ POST /endorsements · /corrections 컨트롤러
           ☑ Outbox 폴링 릴레이 (애그리거트 내 순서 보장) + Kafka 발행
           ☑ 테스트 데이터 시드 (P2026-9000001~9000004)
           ☑ 계약 테스트 (contractTest — 양쪽 레포가 같은 바이트열에 고정)
           ☑ Redis 캐시 (knownAt 명시 조회만 — 불변인 것만 캐시)
Phase 5  청약 + 언더라이팅                                            ☐
Phase 6  운영 강화 (감사, 관측성, 부하 테스트)                        ☐
```

**Phase 0에서 실제로 만들어진 것**

```
policy-domain/          shared/  Temporal ★ · DomainEvent · EventId(ULID) · AggregateRoot
                        shared/vo/  Money (원 단위 정수)
                        policy/  Policy ★ · Coverage · Exclusion · PolicyVersion
                                 KcdRange · CoverageTerms · BenefitYear · PolicySnapshot
                                 PolicyTransitions · SnapshotChecksum
                        policy/event/  PolicyIssued · Endorsed · Corrected ★ · StatusChanged
                        testFixtures/  PolicyFixtures (모듈 간 공유)
policy-application/     port/out/  OutboxAppender · PolicyRepository · PolicyNumberGenerator
                        policy/  PolicySnapshotService · PolicyCommandService
policy-adapter-persistence/  outbox/  Entity · Repository · AppenderAdapter
                             policy/  PolicyJdbcRepository ★ · PolicyNumberJdbcGenerator
                             db/migration/  V1 btree_gist · V2 운영테이블 · V3 Bitemporal
policy-adapter-web/     snapshot/  PolicySnapshotController · Response · CanonicalJson
                        error/  ApiExceptionHandler (409/404 매핑)
policy-bootstrap/       PolicyApplication · SecurityConfig · TimeConfig
                        test/  ArchitectureTest · FlywayMigrationTest
                               PolicyBitemporalIntegrationTest ★ · IntegrationTestBase
policy-underwriting/    package-info.java (Phase 5에서 채움)
policy-adapter-external/ package-info.java (Phase 5에서 채움)
```

`Temporal` 이 이 저장소의 출발점이다. 두 시간축 판정이 순수 함수라
DB 없이 시점 재현성을 검증할 수 있다 (`TemporalTest`). Phase 1의
`Policy.snapshotAsOf(asOf, knownAt)` 가 이 위에 올라간다.

`Money`·`EventId`·`AggregateRoot`·`DomainEvent` 는 claims-platform 과 의도적으로
중복된다. 공유 라이브러리로 빼면 두 바운디드 컨텍스트가 컴파일 타임에 다시 묶인다.
각 파일 상단에 그 이유를 적어 두었다.

Phase 2~4는 claims 저장소 담당이다.
**Phase 1 완료 시 claims 레포에 이슈를 생성해 Phase 2 시작을 알린다.**

## 주의

인수 기준(연령·직업급수·할증률·부담보 기간)과 고지의무 항목은 **설계 예시**이며
실제 보험사 규정으로 검증되지 않았다. 코드에 하드코딩하지 말고 룰 파라미터 테이블로 관리한다.
