-- ─────────────────────────────────────────────────────────────────────────────
-- 테스트 데이터 시드 (로컬 개발 · claims 연동 확인용)
--
-- ★ 이 파일은 운영 마이그레이션 경로에 없다.
--   db/migration 이 아니라 db/seed 에 있고, local 프로파일에서만 flyway.locations 에
--   추가된다. 시드를 마이그레이션에 섞으면 운영 DB 에 가짜 계약이 들어간다.
--
-- ★ 멱등하다.
--   Repeatable 마이그레이션(R__)은 체크섬이 바뀔 때마다 다시 실행된다.
--   이력 테이블에는 EXCLUDE 겹침 방지 제약이 걸려 있어 재실행이 곧 실패다.
--   그래서 맨 앞에서 이미 있으면 빠져나간다.
--
-- 네 가지 계약은 claims 의 골든 케이스와 이 저장소의 Bitemporal 검증을 위한 것이다.
--   P2026-9000001  정상 4세대 실손            → 정상 지급 경로
--   P2026-9000002  척추 부담보 (M40-M54, 5년)  → D-POL-004 부지급 경로
--   P2026-9000003  유예 후 실효                → 사고일 시점 계약상태 판정
--   P2026-9000004  정정 이력 있음              → ★ 같은 asOf, 다른 knownAt
--
-- ★ 일련번호를 9000001 부터 쓴다.
--   계약번호는 PolicyNo 가 P{연도}-{7자리} 형식을 강제하므로 'SEED' 같은 문자를
--   넣을 수 없다(실제로 그렇게 짰다가 도메인이 거부했다). 대신 시퀀스가 발급하는
--   대역(1부터 증가)과 겹치지 않는 9백만대를 쓴다 — 번호만 보고 시드임을 알 수 있고
--   실제 발급 번호와 충돌하지 않는다.
--
-- docs/design/08-roadmap.md 1-21 ~ 1-24
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    -- 기록시점. 시드가 언제 로드되든 같은 값이어야 knownAt 조회가 재현된다.
    t_최초    CONSTANT TIMESTAMPTZ := '2026-01-01T00:00:00Z';
    t_정정    CONSTANT TIMESTAMPTZ := '2026-05-20T02:00:00Z';
    t_실효    CONSTANT TIMESTAMPTZ := '2026-04-01T00:00:00Z';
BEGIN

IF EXISTS (SELECT 1 FROM policy WHERE policy_no = 'P2026-9000001') THEN
    RAISE NOTICE '시드가 이미 로드되어 있습니다. 건너뜁니다.';
    RETURN;
END IF;

-- ═══ SEED-0001 · 정상 4세대 실손 ════════════════════════════════════════════
INSERT INTO policy (policy_no, product_code, product_name, generation,
        holder_ref, insured_ref, insured_birth_year, relation_to_holder,
        period_from, period_to, effective_date, application_no, uw_case_no)
VALUES ('P2026-9000001', 'MED-INDEM-G4', '4세대 실손의료보험', 'G4',
        'HOLDER-SEED-01', 'CI-seed0001', 1988, 'SELF',
        '2026-01-01', '2031-01-01', '2026-01-01', 'A2026-SEED-0001', 'U-2026-SEED-01');

INSERT INTO policy_version (policy_no, status, valid_from, valid_to,
        recorded_at, change_type, reason, actor_ref)
VALUES ('P2026-9000001', 'IN_FORCE', '2026-01-01', '2031-01-01',
        t_최초, 'CREATE', '계약 성립', 'SEED');

INSERT INTO coverage_version (policy_no, coverage_code, coverage_name, benefit_category,
        treatment_types, insured_amount, coinsurance_rate, min_deductible,
        min_deductible_by_grade, annual_limit, per_visit_limit, annual_count_limit,
        valid_from, valid_to, recorded_at, change_type)
VALUES
    ('P2026-9000001', 'COV-INPT-COVERED', '급여 입원의료비', 'COVERED',
     ARRAY['INPATIENT'], 50000000, 0.2000, NULL, NULL, 50000000, NULL, NULL,
     '2026-01-01', '2031-01-01', t_최초, 'CREATE'),
    ('P2026-9000001', 'COV-OUTP-COVERED', '급여 통원의료비', 'COVERED',
     ARRAY['OUTPATIENT'], 200000, 0.2000, NULL,
     '{"CLINIC":10000,"HOSPITAL":15000,"GENERAL":15000,"TERTIARY":20000}'::jsonb,
     NULL, 200000, 100, '2026-01-01', '2031-01-01', t_최초, 'CREATE'),
    ('P2026-9000001', 'COV-OUTP-UNCOVERED', '비급여 통원의료비', 'UNCOVERED',
     ARRAY['OUTPATIENT'], 200000, 0.3000, 30000, NULL,
     NULL, 200000, 100, '2026-01-01', '2031-01-01', t_최초, 'CREATE');

-- ═══ SEED-0002 · 척추 부담보 5년 ════════════════════════════════════════════
-- claims 의 부지급 골든 케이스: 사고일 2026-03-14, 주상병 M51.2 → D-POL-004
INSERT INTO policy (policy_no, product_code, product_name, generation,
        holder_ref, insured_ref, insured_birth_year, relation_to_holder,
        period_from, period_to, effective_date, application_no, uw_case_no)
VALUES ('P2026-9000002', 'MED-INDEM-G4', '4세대 실손의료보험', 'G4',
        'HOLDER-SEED-02', 'CI-seed0002', 1979, 'SELF',
        '2026-01-01', '2031-01-01', '2026-01-01', 'A2026-SEED-0002', 'U-2026-SEED-02');

INSERT INTO policy_version (policy_no, status, valid_from, valid_to,
        recorded_at, change_type, reason, actor_ref)
VALUES ('P2026-9000002', 'IN_FORCE', '2026-01-01', '2031-01-01',
        t_최초, 'CREATE', '계약 성립', 'SEED');

INSERT INTO coverage_version (policy_no, coverage_code, coverage_name, benefit_category,
        treatment_types, insured_amount, coinsurance_rate, min_deductible,
        min_deductible_by_grade, annual_limit, per_visit_limit, annual_count_limit,
        valid_from, valid_to, recorded_at, change_type)
VALUES
    ('P2026-9000002', 'COV-INPT-COVERED', '급여 입원의료비', 'COVERED',
     ARRAY['INPATIENT'], 50000000, 0.2000, NULL, NULL, 50000000, NULL, NULL,
     '2026-01-01', '2031-01-01', t_최초, 'CREATE'),
    ('P2026-9000002', 'COV-OUTP-COVERED', '급여 통원의료비', 'COVERED',
     ARRAY['OUTPATIENT'], 200000, 0.2000, NULL,
     '{"CLINIC":10000,"HOSPITAL":15000,"GENERAL":15000,"TERTIARY":20000}'::jsonb,
     NULL, 200000, 100, '2026-01-01', '2031-01-01', t_최초, 'CREATE');

-- 부담보는 5년. 계약기간(2031-01-01)보다 먼저 끝난다 —
-- 2031-01-01 사고는 부담보가 풀린 상태라는 것이 claims 쪽 경계 케이스다.
INSERT INTO exclusion_version (policy_no, exclusion_id, type, target, kcd_ranges,
        reason, uw_case_no, valid_from, valid_to, recorded_at, change_type)
VALUES ('P2026-9000002', 'EXC-2026-900002', 'BODY_PART', '척추 및 그 부속기관',
        ARRAY['M40-M54'], '5년 내 수술 이력', 'U-2026-SEED-02',
        '2026-01-01', '2031-01-01', t_최초, 'CREATE');

-- ═══ SEED-0003 · 유예 후 실효 ═══════════════════════════════════════════════
-- 사고일이 어느 구간에 걸리느냐로 판정이 갈린다.
--   ~2026-02-01  IN_FORCE   정상
--   ~2026-04-01  GRACE      납입최고 중. 보장은 유지된다
--   2026-04-01~  LAPSED     실효. 부지급
INSERT INTO policy (policy_no, product_code, product_name, generation,
        holder_ref, insured_ref, insured_birth_year, relation_to_holder,
        period_from, period_to, effective_date, application_no, uw_case_no)
VALUES ('P2026-9000003', 'MED-INDEM-G4', '4세대 실손의료보험', 'G4',
        'HOLDER-SEED-03', 'CI-seed0003', 1995, 'SELF',
        '2026-01-01', '2031-01-01', '2026-01-01', 'A2026-SEED-0003', 'U-2026-SEED-03');

INSERT INTO policy_version (policy_no, status, valid_from, valid_to,
        recorded_at, change_type, reason, actor_ref)
VALUES
    ('P2026-9000003', 'IN_FORCE', '2026-01-01', '2026-02-01',
     t_최초, 'CREATE', '계약 성립', 'SEED'),
    ('P2026-9000003', 'GRACE', '2026-02-01', '2026-04-01',
     t_최초, 'ENDORSEMENT', '보험료 미납 — 납입최고 발송', 'BATCH-PREMIUM'),
    -- 최고 발송 기록 없이 실효시키지 않는다(절대 규칙 12). 위 GRACE 구간이 그 기록이다.
    ('P2026-9000003', 'LAPSED', '2026-04-01', '2031-01-01',
     t_실효, 'ENDORSEMENT', '납입최고 기간 경과', 'BATCH-PREMIUM');

INSERT INTO coverage_version (policy_no, coverage_code, coverage_name, benefit_category,
        treatment_types, insured_amount, coinsurance_rate, annual_limit,
        valid_from, valid_to, recorded_at, change_type)
VALUES ('P2026-9000003', 'COV-INPT-COVERED', '급여 입원의료비', 'COVERED',
        ARRAY['INPATIENT'], 50000000, 0.2000, 50000000,
        '2026-01-01', '2031-01-01', t_최초, 'CREATE');

-- ═══ SEED-0004 · ★ 정정 이력 있음 ═══════════════════════════════════════════
-- 이 저장소의 존재 이유를 보여주는 계약이다.
--
--   asOf=2026-03-14, knownAt < 2026-05-20  → 부담보 있음 (4월의 부지급 근거)
--   asOf=2026-03-14, knownAt >= 2026-05-20 → 부담보 없음 (지금 아는 진실)
--
-- 같은 사고일인데 조회 시점에 따라 답이 다르다. 그래서 claims 가 4월의 부지급을
-- 나중에도 설명할 수 있고, 동시에 재심사 대상임을 알 수 있다.
INSERT INTO policy (policy_no, product_code, product_name, generation,
        holder_ref, insured_ref, insured_birth_year, relation_to_holder,
        period_from, period_to, effective_date, application_no, uw_case_no)
VALUES ('P2026-9000004', 'MED-INDEM-G4', '4세대 실손의료보험', 'G4',
        'HOLDER-SEED-04', 'CI-seed0004', 1983, 'SELF',
        '2026-01-01', '2031-01-01', '2026-01-01', 'A2026-SEED-0004', 'U-2026-SEED-04');

INSERT INTO policy_version (policy_no, status, valid_from, valid_to,
        recorded_at, change_type, reason, actor_ref)
VALUES ('P2026-9000004', 'IN_FORCE', '2026-01-01', '2031-01-01',
        t_최초, 'CREATE', '계약 성립', 'SEED');

INSERT INTO coverage_version (policy_no, coverage_code, coverage_name, benefit_category,
        treatment_types, insured_amount, coinsurance_rate, annual_limit,
        valid_from, valid_to, recorded_at, change_type)
VALUES ('P2026-9000004', 'COV-INPT-COVERED', '급여 입원의료비', 'COVERED',
        ARRAY['INPATIENT'], 50000000, 0.2000, 50000000,
        '2026-01-01', '2031-01-01', t_최초, 'CREATE');

-- 착오로 등록됐던 부담보. 행을 지우지 않는다 — 대체 마킹만 한다.
-- superseded_by_correction = TRUE 가 "이 사실 자체가 틀렸었다"는 표시이고,
-- 스냅샷 버전은 이것만 센다(변경은 세지 않는다).
INSERT INTO exclusion_version (policy_no, exclusion_id, type, target, kcd_ranges,
        reason, uw_case_no, valid_from, valid_to,
        recorded_at, superseded_at, superseded_by_correction, change_type)
VALUES ('P2026-9000004', 'EXC-2026-900004', 'BODY_PART', '무릎 및 그 부속기관',
        ARRAY['M22-M23'], '착오 등록 (정정됨)', 'U-2026-SEED-04',
        '2026-01-01', '2031-01-01', t_최초, t_정정, TRUE, 'CREATE');

INSERT INTO correction_log (policy_no, correction_type, affected_elements,
        scope_valid_from, scope_valid_to, previous_version, new_version,
        reason, requested_by, approved_by, corrected_at)
VALUES ('P2026-9000004', 'REMOVE_EXCLUSION', ARRAY['EXCLUSION'],
        '2026-01-01', '2031-01-01', 1, 2,
        '인수심사 착오 — 타인의 고지사항이 잘못 반영됨',
        'UW-SEED-REQ', 'UW-SEED-MGR', t_정정);

RAISE NOTICE '시드 4건 로드 완료 (P2026-9000001~9000004)';

END $$;
