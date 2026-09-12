-- ─────────────────────────────────────────────────────────────────────────────
-- V3. 계약 Bitemporal 이력 (Phase 1)
--
-- 이 스키마의 모든 난이도는 "과거를 재현한다"는 요구에서 나온다.
--
--   유효시간 (valid_from / valid_to)         "2026-03-14에 이 계약은 어땠나?"
--   기록시간 (recorded_at / superseded_at)   "그 답을 우리는 언제부터 알았나?"
--
-- 핵심 규칙: 기존 행을 UPDATE하지 않는다.
--   변경(Endorsement) = 기존 구간을 닫고 새 행 INSERT
--   정정(Correction)  = superseded_at 마킹(유일하게 허용되는 UPDATE) + 새 행 INSERT
--
-- docs/design/06-data-model.md §3
-- ─────────────────────────────────────────────────────────────────────────────

-- ─── 계약 식별 정보 (불변) ────────────────────────────────────────────────────
-- 변하는 것은 여기 두지 않는다. 상태·담보·부담보는 전부 아래 이력 테이블로 간다.
CREATE TABLE policy (
    policy_no          VARCHAR(24)  PRIMARY KEY,
    product_code       VARCHAR(32)  NOT NULL,
    product_name       VARCHAR(100) NOT NULL,
    generation         VARCHAR(8)   NOT NULL,

    holder_ref         VARCHAR(64)  NOT NULL,
    insured_ref        VARCHAR(64)  NOT NULL,   -- CI/내부 고객키. 주민등록번호 아님
    insured_birth_year INT          NOT NULL,   -- 전체 생년월일은 심사에 불필요하다
    relation_to_holder VARCHAR(16)  NOT NULL,

    period_from        DATE         NOT NULL,
    period_to          DATE         NOT NULL,
    effective_date     DATE         NOT NULL,   -- max(승낙일, 초회보험료 납입일)

    application_no     VARCHAR(24)  NULL,
    uw_case_no         VARCHAR(24)  NULL,       -- 부담보 역추적의 시작점

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT policy_period_valid CHECK (period_from < period_to),
    CONSTRAINT policy_effective_in_period
        CHECK (effective_date >= period_from AND effective_date < period_to)
);

CREATE INDEX idx_policy_insured ON policy (insured_ref);


-- ─── 계약 상태 이력 ──────────────────────────────────────────────────────────
CREATE TABLE policy_version (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_no     VARCHAR(24)  NOT NULL REFERENCES policy(policy_no),
    status        VARCHAR(16)  NOT NULL,

    valid_from    DATE         NOT NULL,
    valid_to      DATE         NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    superseded_at TIMESTAMPTZ  NULL,

    change_type   VARCHAR(16)  NOT NULL,
    reason        TEXT         NULL,
    actor_ref     VARCHAR(64)  NOT NULL,

    CONSTRAINT policy_version_range CHECK (valid_from < valid_to),
    CONSTRAINT policy_version_change_type
        CHECK (change_type IN ('CREATE', 'ENDORSEMENT', 'CORRECTION'))
);

-- ★ 유효한 기록끼리 기간이 겹칠 수 없다.
-- 애플리케이션 로직이 틀려도 모순된 이력이 저장되지 않는다.
-- btree_gist 확장이 필요하다 (V1__extensions.sql).
ALTER TABLE policy_version ADD CONSTRAINT policy_version_no_overlap
EXCLUDE USING gist (
    policy_no WITH =,
    daterange(valid_from, valid_to) WITH &&
) WHERE (superseded_at IS NULL);

CREATE INDEX idx_policy_version_asof ON policy_version
    (policy_no, valid_from, valid_to, recorded_at)
    WHERE superseded_at IS NULL;


-- ─── 담보 이력 ───────────────────────────────────────────────────────────────
CREATE TABLE coverage_version (
    id                      BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_no               VARCHAR(24)  NOT NULL REFERENCES policy(policy_no),
    coverage_code           VARCHAR(32)  NOT NULL,
    coverage_name           VARCHAR(100) NOT NULL,
    benefit_category        VARCHAR(20)  NOT NULL,
    treatment_types         TEXT[]       NOT NULL,
    insured_amount          BIGINT       NOT NULL,   -- 원 단위 정수

    coinsurance_rate        NUMERIC(5,4) NOT NULL,
    min_deductible          BIGINT       NULL,       -- NULL = 미적용(입원)
    min_deductible_by_grade JSONB        NULL,       -- 통원 요양기관 종별 차등
    annual_limit            BIGINT       NULL,
    per_visit_limit         BIGINT       NULL,
    annual_count_limit      INT          NULL,
    waiting_period_end      DATE         NULL,

    valid_from              DATE         NOT NULL,
    valid_to                DATE         NOT NULL,
    recorded_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    superseded_at           TIMESTAMPTZ  NULL,
    change_type             VARCHAR(16)  NOT NULL,

    CONSTRAINT coverage_version_range CHECK (valid_from < valid_to),
    CONSTRAINT coverage_rate_range CHECK (coinsurance_rate >= 0 AND coinsurance_rate <= 1),
    CONSTRAINT coverage_amounts_non_negative CHECK (
        insured_amount >= 0
        AND (min_deductible IS NULL OR min_deductible >= 0)
        AND (annual_limit IS NULL OR annual_limit >= 0)
        AND (per_visit_limit IS NULL OR per_visit_limit >= 0)
        AND (annual_count_limit IS NULL OR annual_count_limit >= 0)
    ),
    -- 최소공제금액을 정액과 종별표로 동시에 지정할 수 없다
    CONSTRAINT coverage_deductible_unambiguous CHECK (
        min_deductible IS NULL OR min_deductible_by_grade IS NULL
    ),
    CONSTRAINT coverage_version_change_type
        CHECK (change_type IN ('CREATE', 'ENDORSEMENT', 'CORRECTION')),
    CONSTRAINT coverage_benefit_category
        CHECK (benefit_category IN ('COVERED', 'UNCOVERED', 'MAJOR_UNCOVERED'))
);

-- ★ 같은 담보의 유효구간이 겹칠 수 없다
ALTER TABLE coverage_version ADD CONSTRAINT coverage_version_no_overlap
EXCLUDE USING gist (
    policy_no     WITH =,
    coverage_code WITH =,
    daterange(valid_from, valid_to) WITH &&
) WHERE (superseded_at IS NULL);

CREATE INDEX idx_coverage_version_asof ON coverage_version
    (policy_no, valid_from, valid_to, recorded_at)
    WHERE superseded_at IS NULL;


-- ─── 부담보 이력 ─────────────────────────────────────────────────────────────
-- claims의 부지급 판정(D-POL-004)에 직결되는 테이블.
CREATE TABLE exclusion_version (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_no     VARCHAR(24)  NOT NULL REFERENCES policy(policy_no),
    exclusion_id  VARCHAR(24)  NOT NULL,   -- 논리 식별자. 버전 간 공유된다
    type          VARCHAR(16)  NOT NULL,
    target        VARCHAR(200) NOT NULL,
    kcd_ranges    TEXT[]       NOT NULL,
    reason        VARCHAR(200) NOT NULL,
    uw_case_no    VARCHAR(24)  NULL,       -- ★ 역추적 경로

    valid_from    DATE         NOT NULL,
    valid_to      DATE         NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    superseded_at TIMESTAMPTZ  NULL,
    change_type   VARCHAR(16)  NOT NULL,

    CONSTRAINT exclusion_version_range CHECK (valid_from < valid_to),
    CONSTRAINT exclusion_needs_kcd CHECK (array_length(kcd_ranges, 1) >= 1),
    CONSTRAINT exclusion_version_type
        CHECK (type IN ('BODY_PART', 'DISEASE', 'KCD_RANGE')),
    CONSTRAINT exclusion_version_change_type
        CHECK (change_type IN ('CREATE', 'ENDORSEMENT', 'CORRECTION'))
);

-- 같은 부담보(논리 식별자)의 유효구간이 겹칠 수 없다
ALTER TABLE exclusion_version ADD CONSTRAINT exclusion_version_no_overlap
EXCLUDE USING gist (
    policy_no    WITH =,
    exclusion_id WITH =,
    daterange(valid_from, valid_to) WITH &&
) WHERE (superseded_at IS NULL);

CREATE INDEX idx_exclusion_version_asof ON exclusion_version
    (policy_no, valid_from, valid_to, recorded_at)
    WHERE superseded_at IS NULL;

CREATE INDEX idx_exclusion_kcd ON exclusion_version USING gin (kcd_ranges);


-- ─── 정정 이력 ───────────────────────────────────────────────────────────────
-- 과거 사실을 바꾸는 행위다. 요청자와 승인자를 분리하고 기록을 불변으로 둔다.
CREATE TABLE correction_log (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_no         VARCHAR(24)  NOT NULL REFERENCES policy(policy_no),
    correction_type   VARCHAR(32)  NOT NULL,
    affected_elements TEXT[]       NOT NULL,
    scope_valid_from  DATE         NOT NULL,
    scope_valid_to    DATE         NOT NULL,
    previous_version  INT          NOT NULL,
    new_version       INT          NOT NULL,
    reason            TEXT         NOT NULL,
    requested_by      VARCHAR(64)  NOT NULL,
    approved_by       VARCHAR(64)  NOT NULL,
    corrected_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- 요청자와 승인자가 같으면 안 된다. 단독으로 과거를 바꿀 수 없게 한다.
    CONSTRAINT correction_requires_separate_approver CHECK (requested_by <> approved_by)
);

CREATE INDEX idx_correction_policy ON correction_log (policy_no, corrected_at DESC);


-- ─────────────────────────────────────────────────────────────────────────────
-- 이력 불변 강제
--
-- superseded_at 외의 컬럼을 UPDATE하면 과거가 바뀐다. 그러면 claims가 내린
-- 판단을 재현할 수 없고, 이 시스템의 존재 이유가 사라진다.
-- 애플리케이션 버그가 있어도 DB가 거부한다.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION reject_history_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF (to_jsonb(NEW) - 'superseded_at') IS DISTINCT FROM (to_jsonb(OLD) - 'superseded_at') THEN
        RAISE EXCEPTION
            '이력 레코드는 superseded_at 외에는 수정할 수 없습니다 (table=%, id=%). '
            '변경은 새 행, 정정은 superseded_at 마킹 + 새 행으로 표현하세요.',
            TG_TABLE_NAME, OLD.id;
    END IF;
    IF OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at THEN
        RAISE EXCEPTION
            '이미 정정된 기록의 superseded_at은 다시 바꿀 수 없습니다 (table=%, id=%).',
            TG_TABLE_NAME, OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER policy_version_immutable
    BEFORE UPDATE ON policy_version
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

CREATE TRIGGER coverage_version_immutable
    BEFORE UPDATE ON coverage_version
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

CREATE TRIGGER exclusion_version_immutable
    BEFORE UPDATE ON exclusion_version
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- 정정 이력은 아예 수정·삭제할 수 없다
CREATE RULE correction_log_no_update AS ON UPDATE TO correction_log DO INSTEAD NOTHING;
CREATE RULE correction_log_no_delete AS ON DELETE TO correction_log DO INSTEAD NOTHING;


-- ─── 계약번호 시퀀스 ─────────────────────────────────────────────────────────
CREATE SEQUENCE policy_no_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE exclusion_id_seq START WITH 1 INCREMENT BY 1;
