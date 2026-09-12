-- ─────────────────────────────────────────────────────────────────────────────
-- V2. 기반 인프라 테이블 (Phase 0)
--
-- 업무 테이블(policy, *_version, application, underwriting_case)은 Phase 1·5에서
-- 추가된다. 여기서는 모든 Phase가 공통으로 쓰는 운영 테이블만 만든다.
--
-- docs/design/04-events-and-integration.md §4, §5
-- docs/design/06-data-model.md §7
-- ─────────────────────────────────────────────────────────────────────────────

-- ─── Transactional Outbox ────────────────────────────────────────────────────
-- 상태 변경과 이벤트 저장을 한 트랜잭션으로 묶는다.
-- 커밋되면 이벤트도 있고, 롤백되면 이벤트도 없다.
-- 실제 Kafka 발행은 별도 릴레이가 담당한다 (Phase 1).
CREATE TABLE outbox_event (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       VARCHAR(26)  NOT NULL UNIQUE,   -- ULID. 시간순 정렬 가능
    event_type     VARCHAR(64)  NOT NULL,
    event_version  INT          NOT NULL DEFAULT 1,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,          -- Kafka 파티션 키 = 순서 보장 단위
    envelope       JSONB        NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT         NULL,
    published_at   TIMESTAMPTZ  NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT outbox_status_check
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 릴레이는 PENDING만 조회한다. 발행 완료 건은 인덱스에서 제외해 크기를 유지한다.
CREATE INDEX idx_outbox_pending ON outbox_event (status, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate ON outbox_event (aggregate_type, aggregate_id, occurred_at);


-- ─── 이벤트 소비 멱등성 ───────────────────────────────────────────────────────
-- Outbox는 최소 1회(at-least-once) 전달을 보장한다. 정확히 1회는 보장하지 않는다.
-- 발행 후 status 갱신 전에 죽으면 재발행되므로, 소비자가 멱등해야 한다.
CREATE TABLE processed_event (
    consumer_group VARCHAR(64) NOT NULL,
    event_id       VARCHAR(26) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);


-- ─── API 멱등성 ──────────────────────────────────────────────────────────────
-- 상태를 바꾸는 요청은 Idempotency-Key 헤더를 요구한다.
-- 같은 키 + 같은 요청  → 저장된 응답 반환 (재처리하지 않음)
-- 같은 키 + 다른 요청  → 422
CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(64)  PRIMARY KEY,
    endpoint        VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,    -- sha256(정규화된 요청 본문)
    status          VARCHAR(16)  NOT NULL,
    response_status INT          NULL,
    response_body   JSONB        NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT idempotency_status_check
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);
