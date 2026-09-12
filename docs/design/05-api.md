# 05. API 명세 — business-support

Base: `/api/v1`
인증: `Authorization: Bearer {token}` (사용자) / `X-Service-Token` (서비스 간)

> 공통 규약(오류 형식, 멱등성, 페이지네이션, 버전 정책)은 claims와 동일하다.
> 정본: `insurance-claims-platform/docs/design/05-api.md` §1, §6, §7

---

## 1. 계약 스냅샷 API ★

**이 저장소에서 가장 중요한 엔드포인트.** claims의 심사 근거가 여기서 나온다.

### 1.1 요청

```http
GET /api/v1/policies/{policyNo}/snapshot?asOf=2026-03-14&insuredRef=CI-xxxx&knownAt=2026-04-02T10:15:00%2B09:00
X-Service-Token: {token}
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `policyNo` | ✅ | 계약번호 (경로) |
| `asOf` | ✅ | **유효시간 기준일** = 사고일. 이 시점의 계약 상태를 반환 |
| `insuredRef` | ✅ | 피보험자 식별자. 계약과 불일치 시 404 |
| `knownAt` | ❌ | **기록시간 기준.** 생략 시 현재 = 지금 아는 최신 사실 |

### 1.2 응답 `200 OK`

```jsonc
{
  "snapshotId": "PSN-20260402-000123",
  "policyNo": "P2026-0001234",
  "asOf": "2026-03-14",
  "knownAt": "2026-04-02T10:15:00+09:00",
  "recordedAt": "2026-04-02T10:15:00+09:00",
  "snapshotVersion": 1,

  "product": {
    "productCode": "MED-INDEM-G4",
    "productName": "4세대 실손의료보험",
    "generation": "G4"
  },

  "policyStatusAsOf": "IN_FORCE",
  "policyPeriod": { "from": "2026-01-01", "to": "2031-01-01" },
  "effectiveDate": "2026-01-01",

  "insured": {
    "insuredRef": "CI-xxxx",
    "birthYear": 1988,
    "relationToHolder": "SELF"
  },

  "coverages": [
    {
      "coverageCode": "COV-INPT-COVERED",
      "name": "급여 입원의료비",
      "treatmentTypes": ["INPATIENT"],
      "benefitCategory": "COVERED",
      "insuredAmount": 50000000,
      "terms": {
        "coinsuranceRate": "0.20",
        "minDeductible": null,
        "annualLimit": 50000000,
        "perVisitLimit": null,
        "annualCountLimit": null
      },
      "waitingPeriodEnd": null,
      "coverageStatusAsOf": "ACTIVE"
    },
    {
      "coverageCode": "COV-OUTP-COVERED",
      "name": "급여 통원의료비",
      "treatmentTypes": ["OUTPATIENT"],
      "benefitCategory": "COVERED",
      "terms": {
        "coinsuranceRate": "0.20",
        "minDeductibleByGrade": {
          "CLINIC": 10000, "HOSPITAL": 15000,
          "GENERAL": 15000, "TERTIARY": 20000
        },
        "perVisitLimit": 200000,
        "annualCountLimit": 100
      },
      "coverageStatusAsOf": "ACTIVE"
    },
    {
      "coverageCode": "COV-OUTP-UNCOVERED",
      "name": "비급여 통원의료비",
      "treatmentTypes": ["OUTPATIENT"],
      "benefitCategory": "UNCOVERED",
      "terms": {
        "coinsuranceRate": "0.30",
        "minDeductible": 30000,
        "perVisitLimit": 200000,
        "annualCountLimit": 100
      },
      "coverageStatusAsOf": "ACTIVE"
    }
  ],

  "exclusions": [
    {
      "exclusionId": "EXC-2026-000045",
      "type": "BODY_PART",
      "target": "척추 및 그 부속기관",
      "kcdRanges": ["M40-M54"],
      "from": "2026-01-01",
      "until": "2031-01-01",
      "reason": "언더라이팅 부담보",
      "uwCaseNo": "U-2026-0012"
    }
  ],

  "premium": {
    "paidThrough": "2026-03-31",
    "inGracePeriod": false,
    "graceEndDate": null
  },

  "benefitYear": {
    "current": "2026",
    "from": "2026-01-01",
    "to": "2027-01-01"
  },

  "checksum": "sha256:9f2c8a1b..."
}
```

### 1.3 응답 규칙

| 규칙 | 내용 |
|---|---|
| **시점 재현성** | 같은 `(policyNo, asOf, insuredRef, knownAt)`은 **영원히 같은 응답** |
| `policyStatusAsOf` | **`asOf` 시점의 상태.** 오늘 상태가 아니다 |
| `coverages` | `asOf` 시점 유효한 담보만 |
| `exclusions` | `asOf` 시점 유효한 부담보만 |
| `checksum` | 본문(checksum 제외) 정규화 JSON의 SHA-256 |
| `benefitYear` | **계약 응당일 기준.** claims의 한도 원장 키 |

### 1.4 제공하지 않는 것 (최소권한)

| 미제공 | 이유 |
|---|---|
| 고지사항 원문 | 민감 건강정보. claims는 결과물(부담보)만 필요 |
| 건강진단 결과 | 동일 |
| 계약자 성명·주소·연락처 | 심사에 불필요 |
| 모집인·수수료 정보 | 심사에 불필요 |
| 보험료 금액 | `paidThrough`와 유예 여부만 필요 |
| 주민등록번호 | 저장하지 않음 |

### 1.5 오류

| 상황 | HTTP | `code` |
|---|---|---|
| 계약 없음 | 404 | `POLICY_NOT_FOUND` |
| `insuredRef` 불일치 | 404 | `POLICY_NOT_FOUND` (존재 여부를 노출하지 않음) |
| `asOf`가 계약 생성 이전 | 404 | `SNAPSHOT_NOT_AVAILABLE` |
| `asOf`가 미래 | 400 | `INVALID_AS_OF` |
| 서비스 토큰 없음 | 401 | `UNAUTHENTICATED` |

### 1.6 시점 조회 동작 예시

```
계약 이력:
  담보 A  valid[2026-01-01 ~ 2026-06-01)  recorded 2026-01-01  superseded null
  담보 A  valid[2026-06-01 ~ 9999-12-31)  recorded 2026-06-01  superseded null
  부담보  valid[2026-01-01 ~ 2031-01-01)  recorded 2026-01-01  superseded 2026-05-20
```

| 요청 | 응답 |
|---|---|
| `asOf=2026-03-14` (knownAt 생략) | 담보 A 구버전, **부담보 없음** (정정 반영), version 2 |
| `asOf=2026-03-14&knownAt=2026-04-02T...` | 담보 A 구버전, **부담보 있음** (정정 전), version 1 |
| `asOf=2026-08-01` | 담보 A 신버전, 부담보 없음 |

> 두 번째 행이 Bitemporal의 존재 이유다. claims가 4월에 부지급한 근거를 재현할 수 있다.

---

## 2. 계약 조회 API

### 2.1 현재 계약 조회

```http
GET /api/v1/policies/{policyNo}
```

```jsonc
{
  "policyNo": "P2026-0001234",
  "status": "IN_FORCE",
  "product": { "productCode": "MED-INDEM-G4", "generation": "G4" },
  "holder": { "name": "홍*동", "relationToInsured": "SELF" },   // 마스킹
  "insured": { "insuredRef": "CI-xxxx", "name": "홍*동" },
  "policyPeriod": { "from": "2026-01-01", "to": "2031-01-01" },
  "coverages": [ /* 현재 유효 담보 */ ],
  "exclusions": [ /* 현재 유효 부담보 */ ],
  "premium": { "amount": 32400, "cycle": "MONTHLY", "paidThrough": "2026-03-31" }
}
```

### 2.2 계약 이력 조회

```http
GET /api/v1/policies/{policyNo}/history?element=EXCLUSION
```

```jsonc
{
  "policyNo": "P2026-0001234",
  "changes": [
    {
      "changeType": "CREATE",
      "element": "EXCLUSION",
      "validFrom": "2026-01-01", "validTo": "2031-01-01",
      "recordedAt": "2026-01-01T09:00:00+09:00",
      "supersededAt": "2026-05-20T11:00:00+09:00",
      "detail": { "target": "척추 및 그 부속기관", "kcdRanges": ["M40-M54"] },
      "reason": "언더라이팅 부담보", "actor": "SYSTEM"
    },
    {
      "changeType": "CORRECTION",
      "element": "EXCLUSION",
      "recordedAt": "2026-05-20T11:00:00+09:00",
      "detail": null,
      "reason": "부담보 조건 착오 입력 정정", "actor": "UW-0007"
    }
  ]
}
```

---

## 3. 청약 API

### 3.1 청약 제출

```http
POST /api/v1/applications
Idempotency-Key: {uuid}
```

```jsonc
{
  "productCode": "MED-INDEM-G4",
  "holder": {
    "name": "홍길동", "birthDate": "1988-05-12",
    "ci": "CI-xxxx", "phone": "010-1234-5678"
  },
  "insured": {
    "ci": "CI-xxxx", "relationToHolder": "SELF",
    "occupationCode": "OFF-001", "occupationClass": 1,
    "heightCm": 175, "weightKg": 70, "smoker": false
  },
  "requestedCoverages": [
    { "coverageCode": "COV-INPT-COVERED",   "insuredAmount": 50000000 },
    { "coverageCode": "COV-OUTP-COVERED",   "insuredAmount": 200000 },
    { "coverageCode": "COV-OUTP-UNCOVERED", "insuredAmount": 200000 }
  ],
  "disclosures": [
    { "code": "DSC-3M-TREATMENT", "answer": false },
    { "code": "DSC-1Y-RETEST",    "answer": false },
    { "code": "DSC-5Y-SURGERY",   "answer": true,
      "detail": "2024-03 요추 추간판탈출증 수술, ○○병원" },
    { "code": "DSC-5Y-MAJOR",     "answer": false }
  ],
  "paymentCycle": "MONTHLY"
}
```

**응답 `201 Created`**

```jsonc
{
  "applicationNo": "A2026-0005678",
  "status": "UNDERWRITING",
  "estimatedPremium": 32400,
  "coolingOffUntil": "2026-01-16",
  "submittedAt": "2026-01-01T09:00:00+09:00"
}
```

> `disclosures[].detail`은 **민감 건강정보**다. 즉시 암호화 저장되고 응답에 반향되지 않는다.

### 3.2 청약 조회

```http
GET /api/v1/applications/{applicationNo}
```

```jsonc
{
  "applicationNo": "A2026-0005678",
  "status": "MEDICAL_REQUIRED",
  "underwriting": {
    "caseNo": "U-2026-0012",
    "status": "MEDICAL_REQUIRED",
    "message": "진단서 제출이 필요합니다.",
    "requiredDocuments": ["DIAGNOSIS_CERT"],
    "dueDate": "2026-01-15"
  }
}
```

> 고객에게는 **회부 사유의 룰 ID를 노출하지 않는다.** 필요한 행동만 안내한다.

### 3.3 청약 철회

```http
POST /api/v1/applications/{applicationNo}/withdrawal
Idempotency-Key: {uuid}
```

청약철회 기간 내에는 무조건 수용. 기간 경과 시 `409`.

---

## 4. 언더라이팅 API (심사자)

권한: `ROLE_UNDERWRITER`

### 4.1 심사 큐

```http
GET /api/v1/underwriting/queue?status=REFERRED&sort=submittedAt,asc
```

```jsonc
{
  "content": [
    {
      "caseNo": "U-2026-0012",
      "applicationNo": "A2026-0005678",
      "referReasons": [
        { "ruleId": "U-DSC-020", "label": "1년 내 추가검사 - 확정진단 전" }
      ],
      "proposedDecision": "REFER",
      "insuredAge": 51,
      "totalInsuredAmount": 50000000,
      "waitingDays": 2
    }
  ]
}
```

### 4.2 심사 상세 (트레이스 포함)

```http
GET /api/v1/underwriting/cases/{caseNo}
```

```jsonc
{
  "caseNo": "U-2026-0012",
  "mode": "AUTO",
  "status": "REFERRED",
  "rulesetVersion": "2026.01",

  "disclosures": [                       // 🔒 복호화. 접근이 audit_log에 기록됨
    { "code": "DSC-5Y-SURGERY", "answer": true,
      "detail": "2024-03 요추 추간판탈출증 수술, ○○병원" }
  ],

  "proposedDecision": "EXCLUDED",
  "proposedExclusions": [
    { "type": "BODY_PART", "target": "척추 및 그 부속기관",
      "kcdRanges": ["M40-M54"], "years": 5 }
  ],

  "traces": [
    {
      "seq": 5, "ruleId": "U-DSC-030", "ruleName": "5년 내 입원·수술 이력",
      "input":  { "disclosureCode": "DSC-5Y-SURGERY", "riskCategory": "MUSCULOSKELETAL",
                  "severity": "MODERATE" },
      "output": { "action": "PROPOSE_EXCLUSION", "kcdRanges": ["M40-M54"], "years": 5 },
      "verdict": "APPLIED"
    }
  ],

  "applicantHistory": { "previousApplications": 1, "previousDeclines": 0 }
}
```

### 4.3 인수 결정

```http
POST /api/v1/underwriting/cases/{caseNo}/decision
Idempotency-Key: {uuid}
```

```jsonc
{
  "decision": "EXCLUDED",
  "exclusions": [
    { "type": "BODY_PART", "target": "척추 및 그 부속기관",
      "kcdRanges": ["M40-M54"], "years": 5 }
  ],
  "ratingPercent": null,
  "overrideReason": null,
  "declineReasonCode": null
}
```

**검증**

| 규칙 | 실패 시 |
|---|---|
| 자동 제안과 다르면 `overrideReason` 필수 | 400 |
| `EXCLUDED`면 `exclusions` 최소 1건 | 400 |
| `DECLINED`면 `declineReasonCode` 필수 | 400 |
| `RATED`면 `ratingPercent` 필수 | 400 |

→ `MANUAL_OVERRIDE` 트레이스 기록 + `underwriting.decided` 이벤트.

### 4.4 추가 서류 요청

```http
POST /api/v1/underwriting/cases/{caseNo}/document-request
```

```jsonc
{ "requiredDocuments": ["DIAGNOSIS_CERT"], "message": "...", "dueDate": "2026-01-15" }
```

---

## 5. 계약 성립 API

```http
POST /api/v1/policies
Idempotency-Key: {uuid}
X-Service-Token: {token}
```

```jsonc
{
  "applicationNo": "A2026-0005678",
  "underwritingCaseNo": "U-2026-0012",
  "consent": {                             // ★ 부담보·할증 인수 시 필수
    "consentedAt": "2026-01-01T08:55:00+09:00",
    "channel": "MOBILE_APP",
    "acknowledgedTerms": ["EXCLUSION_SPINE_5Y"]
  },
  "firstPremiumPaidAt": "2026-01-01T08:58:00+09:00"
}
```

**응답 `201 Created`**

```jsonc
{
  "policyNo": "P2026-0001234",
  "status": "IN_FORCE",
  "effectiveDate": "2026-01-01",           // 승낙일과 초회납입일 중 늦은 날
  "policyPeriod": { "from": "2026-01-01", "to": "2031-01-01" },
  "issuedAt": "2026-01-01T09:00:00+09:00"
}
```

> **동의 기록 없이 부담보 계약을 성립시킬 수 없다** (`409 CONSENT_REQUIRED`).
> 불완전판매를 시스템이 막는다.

---

## 6. 계약 보전 API

### 6.1 계약 변경 (Endorsement)

```http
POST /api/v1/policies/{policyNo}/endorsements
Idempotency-Key: {uuid}
```

```jsonc
{
  "effectiveFrom": "2026-06-01",
  "changes": [
    { "type": "COVERAGE_AMOUNT", "coverageCode": "COV-OUTP-UNCOVERED",
      "newInsuredAmount": 300000 }
  ],
  "reason": "고객 요청"
}
```

→ `policy.endorsed`. **과거 스냅샷은 변하지 않는다.**

### 6.2 소급 정정 (Correction) ★

```http
POST /api/v1/policies/{policyNo}/corrections
Idempotency-Key: {uuid}
X-Service-Token: {token}
```

```jsonc
{
  "correctionType": "REMOVE_EXCLUSION",
  "targetId": "EXC-2026-000045",
  "validFrom": "2026-01-01",
  "validTo": "2031-01-01",
  "reason": "부담보 조건 착오 입력 정정",
  "approvedBy": "UW-MANAGER-003"
}
```

**응답 `200 OK`**

```jsonc
{
  "policyNo": "P2026-0001234",
  "previousSnapshotVersion": 1,
  "newSnapshotVersion": 2,
  "correctedAt": "2026-05-20T11:00:00+09:00",
  "affectedClaimsNotified": true          // policy.corrected 발행됨
}
```

> 정정은 **승인자(`approvedBy`)가 필수**다. 과거 사실을 바꾸는 행위이므로
> 단독으로 실행할 수 없게 만든다. 전 과정이 `audit_log`에 남는다.

### 6.3 부활

```http
POST /api/v1/policies/{policyNo}/reinstatement
Idempotency-Key: {uuid}
```

```jsonc
{
  "arrearsPaidAmount": 97200,
  "redisclosures": [ { "code": "DSC-3M-TREATMENT", "answer": false } ],
  "reinstatedOn": "2026-07-01"
}
```

→ 재심사 수행 + **면책기간 재기산** + `policy.reinstated`.

---

## 7. 보험료 API

| 엔드포인트 | 용도 |
|---|---|
| `POST /api/v1/policies/{policyNo}/premium-payments` | 수납 등록 |
| `GET /api/v1/policies/{policyNo}/premium-schedule` | 납입 일정 |
| `POST /api/v1/policies/{policyNo}/demand-notice` | 납입최고 발송 기록 |

---

## 8. 관리 API

| 엔드포인트 | 용도 |
|---|---|
| `GET /actuator/health` | 헬스체크 |
| `GET /actuator/prometheus` | 지표 |
| `POST /api/v1/admin/outbox/{eventId}/republish` | Outbox 재발행 |
| `POST /api/v1/admin/snapshot-cache/{policyNo}/evict` | 캐시 무효화 |

---

## 다음 문서

- [`06-data-model.md`](06-data-model.md) — Bitemporal 스키마
- [`07-architecture.md`](07-architecture.md) — 모듈 구조
