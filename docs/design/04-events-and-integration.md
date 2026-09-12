# 04. 이벤트 · Outbox · 통합

> 이벤트 설계 원칙·봉투 스키마·Outbox 구조는 **claims와 동일**하다.
> 두 시스템이 같은 규약을 쓰는 것이 운영상 중요하다. 정본: `insurance-claims-platform/docs/design/04-events-and-integration.md`
> 이 문서는 BS 고유의 이벤트와 통합 책임만 다룬다.

---

## 1. 공통 규약 (요약)

| 항목 | 규약 |
|---|---|
| 발행 방식 | **Transactional Outbox** — 상태 변경과 같은 트랜잭션에 INSERT |
| 이벤트 생성 | **애그리거트가 `record()`**, 애플리케이션이 `pullEvents()` |
| ID | ULID (시간순 정렬 가능) |
| 전달 보장 | 최소 1회 (at-least-once) → **소비자가 멱등해야 함** |
| 순서 | Kafka 파티션 키 = `policyNo` |
| 민감정보 | **페이로드에 넣지 않음** |
| 스키마 변경 | 필드 추가는 자유, 삭제·의미 변경은 새 버전 |

### 1.1 봉투

```jsonc
{
  "eventId": "01JBX7K3QM8W2ZP4NRTV9C6DYE",
  "eventType": "policy.corrected",
  "eventVersion": 1,
  "occurredAt": "2026-05-20T11:00:00+09:00",
  "producer": "business-support",
  "aggregateType": "Policy",
  "aggregateId": "P2026-0001234",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "P2026-0001234",
  "causationId": "01JBX7K1...",
  "payload": { /* ... */ }
}
```

---

## 2. 발행 이벤트 카탈로그

### 2.1 청약·언더라이팅 (내부 소비)

| 이벤트 | 발행 시점 | 소비자 |
|---|---|---|
| `application.submitted` | 청약 제출 | UW 파이프라인 트리거 |
| `application.withdrawn` | 청약 철회 | 정리 |
| `underwriting.started` | 심사 개시 | 관측 |
| `underwriting.referred` | UW 회부 | 심사자 큐, 알림 |
| `underwriting.medical_required` | 의적심사 필요 | 고객 안내 |
| `underwriting.decided` | 인수 결정 | 계약 발행, 고객 통지 |
| `underwriting.declined` | 거절 | 고객 통지 |

### 2.2 계약 (claims가 소비) ★

| 이벤트 | 발행 시점 | claims의 반응 | 중요도 |
|---|---|---|---|
| `policy.issued` | 계약 성립 | 읽기모델 생성 | 보통 |
| `policy.endorsed` | **현행 변경** | 읽기모델 갱신 | 보통 |
| `policy.lapsed` | 실효 확정 | 읽기모델 갱신 | 보통 |
| `policy.reinstated` | 부활 | 읽기모델 갱신 | 보통 |
| `policy.terminated` | 해지·만기 | 읽기모델 갱신 | 보통 |
| **`policy.corrected`** | **소급 정정** | **영향 청구 재심사 표시** | **높음** |

### 2.3 보험료

| 이벤트 | 발행 시점 | 소비자 |
|---|---|---|
| `premium.paid` | 보험료 수납 | 계약 상태 평가 |
| `premium.overdue` | 납입기일 경과 | 최고 발송 |
| `premium.demand_sent` | 최고장 발송 | 실효 판정 전제 기록 |

---

## 3. 페이로드

### 3.1 `policy.issued`

```jsonc
{
  "policyNo": "P2026-0001234",
  "productCode": "MED-INDEM-G4",
  "generation": "G4",
  "insuredRef": "CI-xxxx",
  "policyPeriod": { "from": "2026-01-01", "to": "2031-01-01" },
  "effectiveDate": "2026-01-01",
  "status": "IN_FORCE",
  "coverageCodes": ["COV-INPT-COVERED", "COV-OUTP-COVERED", "COV-OUTP-UNCOVERED"],
  "hasExclusions": true,          // 상세는 스냅샷 API로. 이벤트엔 존재 여부만
  "issuedAt": "2026-01-01T09:00:00+09:00"
}
```

> **부담보 상세를 이벤트에 싣지 않는다.** KCD 범위는 건강정보를 추론할 수 있는 정보다.
> claims가 필요할 때 스냅샷 API로 인가받아 가져간다. 이벤트는 "있다/없다"만 알린다.

### 3.2 `policy.corrected` ★

```jsonc
{
  "policyNo": "P2026-0001234",
  "correctionScope": {
    "validFrom": "2026-01-01",
    "validTo": "2031-01-01",
    "affectedElements": ["EXCLUSION"]     // COVERAGE|EXCLUSION|STATUS|PERIOD
  },
  "previousSnapshotVersion": 1,
  "newSnapshotVersion": 2,
  "reason": "부담보 조건 착오 입력 정정",
  "correctedBy": "UW-0007",
  "correctedAt": "2026-05-20T11:00:00+09:00"
}
```

**claims의 처리**

```
correctionScope.validFrom ~ validTo 구간에 accidentDate가 걸친 청구를 조회
  → 미지급 건: 재심사 대상 표시 → 심사자 큐
  → 지급 완료 건: 정정 방향에 따라 추가지급 또는 환수 검토 → 심사자 큐
  → 자동 재심사하지 않는다 (방향 판단에 사람이 필요)
```

### 3.3 `policy.lapsed`

```jsonc
{
  "policyNo": "P2026-0001234",
  "lapsedOn": "2026-04-01",
  "lastPaidThrough": "2026-01-31",
  "graceEndedOn": "2026-03-31",
  "demandNoticeSentAt": "2026-03-10T10:00:00+09:00",   // 최고 이력 (실효 유효성 근거)
  "reinstatableUntil": "2029-04-01"
}
```

> `demandNoticeSentAt`을 싣는 이유: 납입최고 절차 없는 실효는 무효가 될 수 있다.
> 실효 사실과 그 **절차적 정당성**을 함께 전달한다.

---

## 4. 수신 이벤트

### 4.1 `claim.paid` (claims 발행)

```jsonc
{
  "claimNo": "CLM-20260402-000123",
  "policyNo": "P2026-0001234",
  "insuredRef": "CI-xxxx",
  "paidAmount": 124000,
  "paidAt": "2026-04-05T14:22:31+09:00",
  "benefitYear": "2026",
  "treatmentTypes": ["OUTPATIENT"],
  "majorUncoveredUsed": false
}
```

**BS의 처리 — 통계 적재만 한다.**

```java
@KafkaListener(topics = "claims.claim-events.v1", groupId = "bs-loss-statistics")
@Transactional
public void on(EventEnvelope envelope) {
    if (!processedEvents.markProcessed("bs-loss-statistics", envelope.eventId())) return;
    if (!"claim.paid".equals(envelope.eventType())) return;

    lossStatisticsRepository.accumulate(...);   // 손해율 집계 테이블
    // ❌ 계약 상태를 바꾸지 않는다
    // ❌ 보험료를 자동 조정하지 않는다
}
```

**용도**

| 용도 | 설명 |
|---|---|
| 손해율 집계 | 상품·연령·지역별 통계 |
| 갱신 심사 참고 | 갱신 시 UW 입력 |
| 4세대 비급여 이용량 연동 | 비급여 이용 실적 → 보험료 할인·할증 등급 산정 |

> **계약을 자동으로 바꾸지 않는다.** 바꾸면 "청구 처리 장애 → 계약 업무 마비"의 양방향 의존이 생긴다.
> 갱신 시점에 통계를 **조회해서** 쓴다.

### 4.2 `claim.reclaimed`

환수 통계만 적재한다.

---

## 5. Outbox

claims와 동일한 스키마·릴레이 구조를 쓴다.

```sql
CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,
    event_id       VARCHAR(26)  NOT NULL UNIQUE,
    event_type     VARCHAR(64)  NOT NULL,
    event_version  INT          NOT NULL DEFAULT 1,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,       -- policyNo
    envelope       JSONB        NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT         NULL,
    published_at   TIMESTAMPTZ  NULL
);

CREATE INDEX idx_outbox_pending ON outbox_event (status, id) WHERE status = 'PENDING';
```

**릴레이**: 폴링 + `FOR UPDATE SKIP LOCKED` (Phase 1), 필요 시 Debezium CDC로 전환.

---

## 6. 토픽

| 토픽 | 파티션 키 | 보존 | 소비자 |
|---|---|---|---|
| `policy.policy-events.v1` | `policyNo` | 30일 | **claims**, 통계 |
| `policy.application-events.v1` | `applicationNo` | 14일 | 내부 (UW 큐, 알림) |
| `policy.premium-events.v1` | `policyNo` | 30일 | 내부 |
| `policy.dlq.v1` | 원본 키 | 180일 | 운영자 |

---

## 7. 스냅샷 API 제공 책임

이벤트와 별개로, BS의 **가장 중요한 통합 책임**은 동기 스냅샷 API다.

| 책임 | 검증 방법 |
|---|---|
| 시점 재현성 | 같은 `(policyNo, asOf, knownAt)`에 항상 같은 응답 — 테스트 |
| checksum 정확성 | claims와 동일 정규화 알고리즘 — **계약 테스트** |
| 하위 호환 | 계약 테스트가 CI에서 검증 |
| 최소권한 | 고지사항·주소·모집인 정보 미포함 — 응답 스키마 테스트 |
| 성능 | p99 < 300ms — 부하 테스트 |

### 7.1 캐시 전략

```
확정된 과거 스냅샷 (asOf < today, 정정 없음)  → 영구 캐시 가능
현재/미래 스냅샷                              → 짧은 TTL 또는 캐시 안 함
정정 발생 시                                  → 해당 policyNo 캐시 전체 무효화
```

**과거는 변하지 않는다** — Bitemporal 모델의 부수적 이득이다.
`policy.corrected`가 유일한 무효화 트리거다.

---

## 8. 멱등성

| 층 | 수단 |
|---|---|
| API | `Idempotency-Key` 헤더 (청약 제출, 인수 결정, 계약 변경) |
| 이벤트 소비 | `processed_event` 테이블 (`consumer_group` + `event_id`) |
| 외부 연동 | 각 포트별 멱등키 |

claims와 동일한 구현을 쓴다.

---

## 9. 계약 테스트 (BS가 지는 의무)

```
src/test/resources/contracts/        # claims 레포에서 동기화
├── policy-snapshot-response.json
├── policy-snapshot-with-exclusion.json
├── policy-snapshot-lapsed.json
├── policy-snapshot-grace.json
└── events/
    ├── policy.issued.json
    ├── policy.lapsed.json
    └── policy.corrected.json
```

```java
@Tag("contract")
class PolicySnapshotContractTest extends IntegrationTestBase {

    @ParameterizedTest
    @MethodSource("contractFixtures")
    void 응답이_claims의_계약을_만족한다(ContractFixture fixture) {
        var response = mockMvc.perform(get(fixture.request())).andReturn();
        assertThatJson(response).satisfiesContract(fixture.expectedSchema());
    }

    @Test
    void checksum이_claims와_동일한_알고리즘으로_계산된다() {
        // 정규화 JSON 해시가 계약 픽스처의 기대값과 일치
    }
}
```

**CI에 포함한다.** 계약을 깨면 BS의 PR이 머지되지 않는다.

```groovy
task contractTest(type: Test) { useJUnitPlatform { includeTags 'contract' } }
check.dependsOn contractTest
```

---

## 10. 관측성

| 지표 | 용도 |
|---|---|
| `policy.snapshot.requests` | 스냅샷 API 호출량 |
| `policy.snapshot.duration` | **p99 < 300ms 감시** |
| `policy.snapshot.cache_hit_rate` | 캐시 효율 |
| `policy.corrected.count` | **정정 발생 — 많으면 입력 프로세스 문제** |
| `uw.auto_rate` / `uw.refer{ruleId}` / `uw.override_rate` | 언더라이팅 품질 |
| `outbox.pending.age` | 릴레이 건강도 |
| `policy.lapse.count` | 실효 추이 |

### 10.1 알림

| 조건 | 심각도 |
|---|---|
| 스냅샷 API 오류율 > 1% | **P1** — claims 접수 영향 |
| 스냅샷 p99 > 1s | P2 |
| Outbox pending age > 5분 | P2 |
| `policy.corrected` 급증 | P2 — 데이터 품질 문제 신호 |
| DLQ 적재 | P2 |

---

## 다음 문서

- [`05-api.md`](05-api.md) — 스냅샷 API 전체 명세
- [`06-data-model.md`](06-data-model.md) — Bitemporal 스키마
