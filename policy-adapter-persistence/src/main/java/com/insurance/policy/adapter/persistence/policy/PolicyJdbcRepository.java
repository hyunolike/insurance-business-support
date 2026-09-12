package com.insurance.policy.adapter.persistence.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.policy.application.port.out.PolicyRepository;
import com.insurance.policy.domain.policy.ChangeType;
import com.insurance.policy.domain.policy.Coverage;
import com.insurance.policy.domain.policy.CoverageCode;
import com.insurance.policy.domain.policy.CoverageTerms;
import com.insurance.policy.domain.policy.Exclusion;
import com.insurance.policy.domain.policy.ExclusionId;
import com.insurance.policy.domain.policy.ExclusionType;
import com.insurance.policy.domain.policy.Generation;
import com.insurance.policy.domain.policy.InstitutionGrade;
import com.insurance.policy.domain.policy.InsuredInfo;
import com.insurance.policy.domain.policy.InsuredRef;
import com.insurance.policy.domain.policy.KcdRange;
import com.insurance.policy.domain.policy.Policy;
import com.insurance.policy.domain.policy.PolicyNo;
import com.insurance.policy.domain.policy.PolicyPeriod;
import com.insurance.policy.domain.policy.PolicyStatus;
import com.insurance.policy.domain.policy.PolicyVersion;
import com.insurance.policy.domain.policy.ProductRef;
import com.insurance.policy.domain.policy.BenefitCategory;
import com.insurance.policy.domain.policy.TreatmentType;
import com.insurance.policy.domain.shared.Temporal;
import com.insurance.policy.domain.shared.vo.Money;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 계약 저장소 — JdbcTemplate 구현.
 *
 * <p><b>JPA를 쓰지 않는 이유.</b> Hibernate의 더티 체킹은 영속 상태 엔티티의 변경을
 * 자동으로 UPDATE로 바꾼다. Bitemporal 이력에서 그것은 <b>과거를 조용히 변조하는 것</b>이다.
 * DB 트리거가 막아 주긴 하지만, 애초에 UPDATE가 발생할 수 없는 구조가 낫다.
 *
 * <p>여기서 나가는 SQL은 <b>INSERT와 SELECT뿐</b>이며, 유일한 예외가
 * 정정 시 {@code superseded_at} 마킹이다. 그것이 이 시스템에서 허용되는 단 하나의 UPDATE다.
 */
@Repository
public class PolicyJdbcRepository implements PolicyRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PolicyJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 저장
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void save(Policy policy) {
        jdbc.update("""
                INSERT INTO policy (policy_no, product_code, product_name, generation,
                                    holder_ref, insured_ref, insured_birth_year,
                                    relation_to_holder, period_from, period_to,
                                    effective_date, application_no, uw_case_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policy.policyNo().value(),
                policy.product().productCode(),
                policy.product().productName(),
                policy.product().generation().code(),
                policy.holderRef(),
                policy.insured().insuredRef().value(),
                policy.insured().birthYear(),
                policy.insured().relationToHolder().name(),
                policy.period().from(),
                policy.period().to(),
                policy.effectiveDate(),
                policy.applicationNo(),
                policy.uwCaseNo());

        policy.versions().forEach(v -> insertVersion(policy.policyNo(), v));
        policy.allCoverages().forEach(c -> insertCoverage(policy.policyNo(), c));
        policy.allExclusions().forEach(e -> insertExclusion(policy.policyNo(), e));
    }

    /**
     * 변경 반영 — append-only.
     *
     * <p>구간을 닫으려고 {@code valid_to}를 줄이지 않는다. 애그리거트가 만든
     * <b>대체 마킹과 새 행</b>을 그대로 반영할 뿐이다.
     */
    @Override
    public void applyEndorsement(Policy policy) {
        syncTemporals(policy);
    }

    @Override
    public void applyStatusChange(Policy policy) {
        syncTemporals(policy);
    }

    @Override
    public void applyCorrection(Policy policy, CorrectionRecord record) {
        syncTemporals(policy);

        jdbc.update("""
                INSERT INTO correction_log (policy_no, correction_type, affected_elements,
                                            scope_valid_from, scope_valid_to,
                                            previous_version, new_version, reason,
                                            requested_by, approved_by, corrected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.policyNo().value(),
                record.correctionType(),
                record.affectedElements().toArray(new String[0]),
                record.scopeValidFrom(),
                record.scopeValidTo(),
                record.previousVersion(),
                record.newVersion(),
                record.reason(),
                record.requestedBy(),
                record.approvedBy(),
                Timestamp.from(record.correctedAt()));
    }

    /**
     * 애그리거트의 이력 목록을 DB에 반영한다.
     *
     * <p>두 가지만 한다:
     * <ol>
     *   <li><b>대체 마킹</b> — 애그리거트에서 {@code supersededAt}이 설정됐는데 DB에는
     *       아직 NULL인 행에 마킹한다. 이 시스템에서 허용되는 유일한 UPDATE다.</li>
     *   <li><b>새 행 INSERT</b> — DB에 없는 기록을 넣는다.</li>
     * </ol>
     *
     * <p>기존 행의 다른 컬럼은 절대 건드리지 않는다. 트리거가 거부하기도 하지만,
     * 애초에 시도하지 않는 것이 맞다.
     *
     * <p>대체 마킹을 먼저 하는 순서가 중요하다. 새 행을 먼저 넣으면
     * 아직 유효한 기존 행과 기간이 겹쳐 EXCLUDE 제약에 걸린다.
     */
    private void syncTemporals(Policy policy) {
        supersedeVersions(policy);
        supersedeCoverages(policy);
        supersedeExclusions(policy);

        for (PolicyVersion v : policy.versions()) {
            if (v.supersededAt() == null && !versionExists(policy.policyNo(), v)) {
                insertVersion(policy.policyNo(), v);
            }
        }
        for (Coverage c : policy.allCoverages()) {
            if (c.supersededAt() == null && !coverageExists(policy.policyNo(), c)) {
                insertCoverage(policy.policyNo(), c);
            }
        }
        for (Exclusion e : policy.allExclusions()) {
            if (e.supersededAt() == null && !exclusionExists(policy.policyNo(), e)) {
                insertExclusion(policy.policyNo(), e);
            }
        }
    }

    private void supersedeVersions(Policy policy) {
        for (PolicyVersion v : policy.versions()) {
            if (v.supersededAt() == null) {
                continue;
            }
            jdbc.update("""
                    UPDATE policy_version
                       SET superseded_at = ?, superseded_by_correction = ?
                     WHERE policy_no = ? AND status = ? AND valid_from = ? AND valid_to = ?
                       AND superseded_at IS NULL
                    """,
                    Timestamp.from(v.supersededAt()), v.supersededByCorrection(),
                    policy.policyNo().value(), v.status().name(), v.validFrom(), v.validTo());
        }
    }

    private void supersedeCoverages(Policy policy) {
        for (Coverage c : policy.allCoverages()) {
            if (c.supersededAt() == null) {
                continue;
            }
            jdbc.update("""
                    UPDATE coverage_version
                       SET superseded_at = ?, superseded_by_correction = ?
                     WHERE policy_no = ? AND coverage_code = ?
                       AND valid_from = ? AND valid_to = ?
                       AND superseded_at IS NULL
                    """,
                    Timestamp.from(c.supersededAt()), c.supersededByCorrection(),
                    policy.policyNo().value(), c.coverageCode().value(),
                    c.validFrom(), c.validTo());
        }
    }

    private void supersedeExclusions(Policy policy) {
        for (Exclusion e : policy.allExclusions()) {
            if (e.supersededAt() == null) {
                continue;
            }
            jdbc.update("""
                    UPDATE exclusion_version
                       SET superseded_at = ?, superseded_by_correction = ?
                     WHERE policy_no = ? AND exclusion_id = ?
                       AND valid_from = ? AND valid_to = ?
                       AND superseded_at IS NULL
                    """,
                    Timestamp.from(e.supersededAt()), e.supersededByCorrection(),
                    policy.policyNo().value(), e.exclusionId().value(),
                    e.validFrom(), e.validTo());
        }
    }

    /** 유효기간까지 일치하는 현행 행이 이미 있는가. */
    private boolean coverageExists(PolicyNo policyNo, Coverage c) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM coverage_version
                WHERE policy_no = ? AND coverage_code = ?
                  AND valid_from = ? AND valid_to = ? AND superseded_at IS NULL
                """, Integer.class, policyNo.value(), c.coverageCode().value(),
                c.validFrom(), c.validTo());
        return count != null && count > 0;
    }

    private boolean versionExists(PolicyNo policyNo, PolicyVersion v) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_version
                WHERE policy_no = ? AND status = ?
                  AND valid_from = ? AND valid_to = ? AND superseded_at IS NULL
                """, Integer.class, policyNo.value(), v.status().name(),
                v.validFrom(), v.validTo());
        return count != null && count > 0;
    }

    private boolean exclusionExists(PolicyNo policyNo, Exclusion e) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM exclusion_version
                WHERE policy_no = ? AND exclusion_id = ?
                  AND valid_from = ? AND valid_to = ? AND superseded_at IS NULL
                """, Integer.class, policyNo.value(), e.exclusionId().value(),
                e.validFrom(), e.validTo());
        return count != null && count > 0;
    }

    private void insertVersion(PolicyNo policyNo, PolicyVersion v) {
        jdbc.update("""
                INSERT INTO policy_version (policy_no, status, valid_from, valid_to,
                                            recorded_at, superseded_at,
                                            superseded_by_correction, change_type,
                                            reason, actor_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policyNo.value(), v.status().name(), v.validFrom(), v.validTo(),
                Timestamp.from(v.recordedAt()),
                v.supersededAt() == null ? null : Timestamp.from(v.supersededAt()),
                v.supersededByCorrection(),
                v.changeType().name(), v.reason(), v.actorRef());
    }

    private void insertCoverage(PolicyNo policyNo, Coverage c) {
        CoverageTerms t = c.terms();
        jdbc.update("""
                INSERT INTO coverage_version (policy_no, coverage_code, coverage_name,
                        benefit_category, treatment_types, insured_amount,
                        coinsurance_rate, min_deductible, min_deductible_by_grade,
                        annual_limit, per_visit_limit, annual_count_limit,
                        waiting_period_end, valid_from, valid_to, recorded_at,
                        superseded_at, superseded_by_correction, change_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policyNo.value(), c.coverageCode().value(), c.name(),
                c.benefitCategory().name(),
                c.treatmentTypes().stream().map(Enum::name).toArray(String[]::new),
                c.insuredAmount().toWon(),
                t.coinsuranceRate(), t.minDeductible(),
                toJson(t.minDeductibleByGrade()),
                t.annualLimit(), t.perVisitLimit(), t.annualCountLimit(),
                c.waitingPeriodEnd(), c.validFrom(), c.validTo(),
                Timestamp.from(c.recordedAt()),
                c.supersededAt() == null ? null : Timestamp.from(c.supersededAt()),
                c.supersededByCorrection(),
                c.changeType().name());
    }

    private void insertExclusion(PolicyNo policyNo, Exclusion e) {
        jdbc.update("""
                INSERT INTO exclusion_version (policy_no, exclusion_id, type, target,
                        kcd_ranges, reason, uw_case_no, valid_from, valid_to,
                        recorded_at, superseded_at, superseded_by_correction, change_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policyNo.value(), e.exclusionId().value(), e.type().name(), e.target(),
                e.kcdRanges().stream().map(KcdRange::expression).toArray(String[]::new),
                e.reason(), e.uwCaseNo(), e.validFrom(), e.validTo(),
                Timestamp.from(e.recordedAt()),
                e.supersededAt() == null ? null : Timestamp.from(e.supersededAt()),
                e.supersededByCorrection(),
                e.changeType().name());
    }

    private String toJson(Map<InstitutionGrade, Long> byGrade) {
        if (byGrade == null || byGrade.isEmpty()) {
            return null;
        }
        Map<String, Long> plain = new LinkedHashMap<>();
        byGrade.forEach((k, v) -> plain.put(k.name(), v));
        try {
            return objectMapper.writeValueAsString(plain);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("담보 조건 직렬화 실패", ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 조회
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean exists(PolicyNo policyNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy WHERE policy_no = ?", Integer.class,
                policyNo.value());
        return count != null && count > 0;
    }


    /**
     * 변경 이력.
     *
     * <p>세 이력 테이블을 합쳐 시간순으로 돌려준다. 정정된 기록도 포함한다 —
     * "언제 무엇이 바뀌었나"가 목적이므로 무효화된 기록을 숨기면 의미가 없다.
     */
    @Override
    public List<ChangeHistoryEntry> findChangeHistory(PolicyNo policyNo, String element) {
        String sql = """
                SELECT 'STATUS' AS element, change_type, status AS target_ref,
                       valid_from, valid_to, recorded_at, superseded_at,
                       status AS detail, reason, actor_ref
                FROM policy_version WHERE policy_no = :policyNo
                UNION ALL
                SELECT 'COVERAGE', change_type, coverage_code,
                       valid_from, valid_to, recorded_at, superseded_at,
                       coverage_name, NULL, NULL
                FROM coverage_version WHERE policy_no = :policyNo
                UNION ALL
                SELECT 'EXCLUSION', change_type, exclusion_id,
                       valid_from, valid_to, recorded_at, superseded_at,
                       target, reason, uw_case_no
                FROM exclusion_version WHERE policy_no = :policyNo
                ORDER BY recorded_at, element, target_ref
                """.replace(":policyNo", "?");

        List<ChangeHistoryEntry> all = jdbc.query(sql, (rs, i) -> new ChangeHistoryEntry(
                rs.getString("element"),
                rs.getString("change_type"),
                rs.getString("target_ref"),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                toInstant(rs, "recorded_at"),
                toInstant(rs, "superseded_at"),
                rs.getString("detail"),
                rs.getString("reason"),
                rs.getString("actor_ref")),
                policyNo.value(), policyNo.value(), policyNo.value());

        if (element == null || element.isBlank()) {
            return all;
        }
        return all.stream().filter(e -> e.element().equalsIgnoreCase(element)).toList();
    }

    @Override
    public Optional<Policy> load(PolicyNo policyNo) {
        return loadInternal(policyNo, null, null);
    }

    /**
     * ★ 시점 조회.
     *
     * <p>필요한 행만 읽는다. 전체 이력을 메모리에 올리지 않는 것이 이 경로의 핵심이다.
     *
     * <pre>
     * valid_from <= asOf AND asOf < valid_to      -- 유효시간
     * AND recorded_at <= knownAt                   -- 기록시간
     * AND (superseded_at IS NULL OR superseded_at > knownAt)
     * </pre>
     */
    @Override
    public Optional<Policy> findAsOf(PolicyNo policyNo, LocalDate asOf, Instant knownAt) {
        return loadInternal(policyNo, asOf, knownAt);
    }

    private Optional<Policy> loadInternal(PolicyNo policyNo, LocalDate asOf, Instant knownAt) {
        List<PolicyHeader> headers = jdbc.query("""
                SELECT policy_no, product_code, product_name, generation, holder_ref,
                       insured_ref, insured_birth_year, relation_to_holder,
                       period_from, period_to, effective_date, application_no, uw_case_no
                FROM policy WHERE policy_no = ?
                """, HEADER_MAPPER, policyNo.value());
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        PolicyHeader h = headers.get(0);

        boolean pointInTime = asOf != null && knownAt != null;
        Timestamp known = pointInTime ? Timestamp.from(knownAt) : null;

        List<PolicyVersion> versions = pointInTime
                ? jdbc.query(VERSION_ASOF_SQL, VERSION_MAPPER, policyNo.value(), asOf, asOf, known, known, known, known)
                : jdbc.query(VERSION_CURRENT_SQL, VERSION_MAPPER, policyNo.value());

        List<Coverage> coverages = pointInTime
                ? jdbc.query(COVERAGE_ASOF_SQL, coverageMapper(), policyNo.value(), asOf, asOf, known, known, known, known)
                : jdbc.query(COVERAGE_CURRENT_SQL, coverageMapper(), policyNo.value());

        List<Exclusion> exclusions = pointInTime
                ? jdbc.query(EXCLUSION_ASOF_SQL, EXCLUSION_MAPPER, policyNo.value(), asOf, asOf, known, known, known, known)
                : jdbc.query(EXCLUSION_CURRENT_SQL, EXCLUSION_MAPPER, policyNo.value());

        return Optional.of(Policy.rehydrate(
                PolicyNo.of(h.policyNo()),
                new ProductRef(h.productCode(), h.productName(), Generation.of(h.generation())),
                h.holderRef(),
                new InsuredInfo(InsuredRef.of(h.insuredRef()), h.birthYear(),
                        InsuredInfo.Relation.valueOf(h.relationToHolder())),
                new PolicyPeriod(h.periodFrom(), h.periodTo()),
                h.effectiveDate(), h.applicationNo(), h.uwCaseNo(),
                versions, coverages, exclusions));
    }

    /**
     * ★ 시점 조회 조건.
     *
     * <p>두 갈래다. 앞쪽이 <b>그 시점에 유효했던 사실</b>이고, 뒤쪽이
     * <b>스냅샷 버전을 세기 위한 정정 흔적</b>이다.
     *
     * <p>뒤쪽이 없으면 어떻게 되는지가 중요하다. 정정으로 대체된 기록은
     * {@code superseded_at <= knownAt} 이므로 앞쪽 조건에서 걸러진다. 그런데
     * {@code Policy.snapshotAsOf} 는 <b>바로 그 기록들을 세어</b> 스냅샷 버전을 정한다.
     * 걸러버리면 정정을 몇 번 했든 버전이 영원히 1이고,
     * claims 는 "과거가 바뀌었다"는 신호를 받지 못해 재심사를 돌리지 않는다.
     *
     * <p>뒤쪽 갈래로 들어온 행이 응답에 섞이지는 않는다. {@code isEffectiveOn} 이
     * {@code wasKnownAt} 으로 한 번 더 거르기 때문이다 — 세는 데만 쓰인다.
     *
     * <p>유효기간 조건을 뒤쪽에 걸지 않는 것도 의도다. 버전은 "이 계약에 정정이
     * 몇 번 있었나"이지 "이 구간에 몇 번"이 아니다. 구간으로 제한하면 전체 이력을 읽는
     * {@code load()} 와 답이 달라지고, {@code policy.corrected} 이벤트가 알린 버전과도
     * 어긋난다.
     */
    private static final String ASOF_PREDICATE = """
              AND (
                    (    valid_from <= ? AND ? < valid_to
                     AND recorded_at <= ?
                     AND (superseded_at IS NULL OR superseded_at > ?))
                 OR (    superseded_by_correction = TRUE
                     AND superseded_at <= ?
                     AND recorded_at <= ?)
                  )
            """;

    private static final String VERSION_COLUMNS =
            "status, valid_from, valid_to, recorded_at, superseded_at, "
                    + "superseded_by_correction, change_type, reason, actor_ref";
    private static final String VERSION_ASOF_SQL =
            "SELECT " + VERSION_COLUMNS + " FROM policy_version WHERE policy_no = ?" + ASOF_PREDICATE;
    /** 쓰기 경로 — 대체된 기록까지 전부 읽는다. 버전 계산과 중복 INSERT 방지에 필요하다. */
    private static final String VERSION_CURRENT_SQL =
            "SELECT " + VERSION_COLUMNS + " FROM policy_version WHERE policy_no = ?";

    private static final String COVERAGE_COLUMNS = """
            coverage_code, coverage_name, benefit_category, treatment_types, insured_amount,
            coinsurance_rate, min_deductible, min_deductible_by_grade, annual_limit,
            per_visit_limit, annual_count_limit, waiting_period_end,
            valid_from, valid_to, recorded_at, superseded_at, superseded_by_correction,
            change_type
            """;
    private static final String COVERAGE_ASOF_SQL =
            "SELECT " + COVERAGE_COLUMNS + " FROM coverage_version WHERE policy_no = ?" + ASOF_PREDICATE;
    private static final String COVERAGE_CURRENT_SQL =
            "SELECT " + COVERAGE_COLUMNS + " FROM coverage_version WHERE policy_no = ?";

    private static final String EXCLUSION_COLUMNS = """
            exclusion_id, type, target, kcd_ranges, reason, uw_case_no,
            valid_from, valid_to, recorded_at, superseded_at, superseded_by_correction,
            change_type
            """;
    private static final String EXCLUSION_ASOF_SQL =
            "SELECT " + EXCLUSION_COLUMNS + " FROM exclusion_version WHERE policy_no = ?" + ASOF_PREDICATE;
    private static final String EXCLUSION_CURRENT_SQL =
            "SELECT " + EXCLUSION_COLUMNS + " FROM exclusion_version WHERE policy_no = ?";

    private record PolicyHeader(String policyNo, String productCode, String productName,
                                String generation, String holderRef, String insuredRef,
                                int birthYear, String relationToHolder, LocalDate periodFrom,
                                LocalDate periodTo, LocalDate effectiveDate,
                                String applicationNo, String uwCaseNo) {
    }

    private static final RowMapper<PolicyHeader> HEADER_MAPPER = (rs, i) -> new PolicyHeader(
            rs.getString("policy_no"), rs.getString("product_code"), rs.getString("product_name"),
            rs.getString("generation"), rs.getString("holder_ref"), rs.getString("insured_ref"),
            rs.getInt("insured_birth_year"), rs.getString("relation_to_holder"),
            rs.getObject("period_from", LocalDate.class),
            rs.getObject("period_to", LocalDate.class),
            rs.getObject("effective_date", LocalDate.class),
            rs.getString("application_no"), rs.getString("uw_case_no"));

    private static final RowMapper<PolicyVersion> VERSION_MAPPER = (rs, i) ->
            PolicyVersionReconstructor.of(
                    PolicyStatus.valueOf(rs.getString("status")),
                    rs.getObject("valid_from", LocalDate.class),
                    rs.getObject("valid_to", LocalDate.class),
                    toInstant(rs, "recorded_at"), toInstant(rs, "superseded_at"),
                    rs.getBoolean("superseded_by_correction"),
                    ChangeType.valueOf(rs.getString("change_type")),
                    rs.getString("reason"), rs.getString("actor_ref"));

    private RowMapper<Coverage> coverageMapper() {
        return (rs, i) -> CoverageReconstructor.of(
                CoverageCode.of(rs.getString("coverage_code")),
                rs.getString("coverage_name"),
                BenefitCategory.valueOf(rs.getString("benefit_category")),
                readEnumArray(rs.getArray("treatment_types"), TreatmentType::valueOf),
                Money.ofWon(rs.getLong("insured_amount")),
                new CoverageTerms(
                        rs.getBigDecimal("coinsurance_rate"),
                        readNullableLong(rs, "min_deductible"),
                        readGradeMap(rs.getString("min_deductible_by_grade")),
                        readNullableLong(rs, "annual_limit"),
                        readNullableLong(rs, "per_visit_limit"),
                        readNullableInt(rs, "annual_count_limit")),
                rs.getObject("waiting_period_end", LocalDate.class),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                toInstant(rs, "recorded_at"), toInstant(rs, "superseded_at"),
                rs.getBoolean("superseded_by_correction"),
                ChangeType.valueOf(rs.getString("change_type")));
    }

    private static final RowMapper<Exclusion> EXCLUSION_MAPPER = (rs, i) ->
            ExclusionReconstructor.of(
                    ExclusionId.of(rs.getString("exclusion_id")),
                    ExclusionType.valueOf(rs.getString("type")),
                    rs.getString("target"),
                    readStringArray(rs.getArray("kcd_ranges")).stream()
                            .map(KcdRange::parse).toList(),
                    rs.getString("reason"), rs.getString("uw_case_no"),
                    rs.getObject("valid_from", LocalDate.class),
                    rs.getObject("valid_to", LocalDate.class),
                    toInstant(rs, "recorded_at"), toInstant(rs, "superseded_at"),
                    rs.getBoolean("superseded_by_correction"),
                    ChangeType.valueOf(rs.getString("change_type")));

    private Map<InstitutionGrade, Long> readGradeMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Long> plain = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Long.class));
            Map<InstitutionGrade, Long> result = new EnumMap<>(InstitutionGrade.class);
            plain.forEach((k, v) -> result.put(InstitutionGrade.valueOf(k), v));
            return result;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("담보 조건 역직렬화 실패: " + json, ex);
        }
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Long readNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer readNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> readStringArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.asList((String[]) array.getArray());
    }

    private static <E extends Enum<E>> java.util.Set<E> readEnumArray(
            Array array, java.util.function.Function<String, E> parser) throws SQLException {
        List<E> values = new ArrayList<>();
        for (String s : readStringArray(array)) {
            values.add(parser.apply(s));
        }
        return java.util.Set.copyOf(values);
    }

    /**
     * 저장된 이력을 도메인 객체로 되살리는 헬퍼.
     *
     * <p>도메인의 팩토리는 <b>새 사실을 만드는 용도</b>라 {@code supersededAt}을 받지 않는다.
     * 이미 정정된 과거 기록까지 그대로 복원해야 하는 것은 영속성 계층의 사정이므로
     * 어댑터 안에 둔다. {@link Temporal}의 계약은 동일하게 지킨다.
     */
    private static final class PolicyVersionReconstructor {
        private PolicyVersionReconstructor() {
        }

        static PolicyVersion of(PolicyStatus status, LocalDate validFrom, LocalDate validTo,
                                Instant recordedAt, Instant supersededAt,
                                boolean supersededByCorrection, ChangeType changeType,
                                String reason, String actorRef) {
            PolicyVersion base = changeType == ChangeType.CREATE
                    ? PolicyVersion.create(status, validFrom, validTo, recordedAt, actorRef)
                    : PolicyVersion.endorsement(status, validFrom, validTo, recordedAt,
                            reason, actorRef);
            return supersededAt == null
                    ? base : base.superseded(supersededAt, supersededByCorrection);
        }
    }

    private static final class CoverageReconstructor {
        private CoverageReconstructor() {
        }

        static Coverage of(CoverageCode code, String name, BenefitCategory category,
                           java.util.Set<TreatmentType> types, Money insuredAmount,
                           CoverageTerms terms, LocalDate waitingPeriodEnd,
                           LocalDate validFrom, LocalDate validTo, Instant recordedAt,
                           Instant supersededAt, boolean supersededByCorrection,
                           ChangeType changeType) {
            Coverage base = Coverage.create(code, name, category, types, insuredAmount, terms,
                    waitingPeriodEnd, validFrom, validTo, recordedAt);
            if (changeType == ChangeType.ENDORSEMENT) {
                base = base.withTerms(terms, insuredAmount, validFrom, validTo, recordedAt);
            }
            return supersededAt == null
                    ? base : base.superseded(supersededAt, supersededByCorrection);
        }
    }

    private static final class ExclusionReconstructor {
        private ExclusionReconstructor() {
        }

        static Exclusion of(ExclusionId id, ExclusionType type, String target,
                            List<KcdRange> ranges, String reason, String uwCaseNo,
                            LocalDate validFrom, LocalDate validTo, Instant recordedAt,
                            Instant supersededAt, boolean supersededByCorrection,
                            ChangeType changeType) {
            Exclusion base = Exclusion.create(id, type, target, ranges, reason, uwCaseNo,
                    validFrom, validTo, recordedAt);
            return supersededAt == null
                    ? base : base.superseded(supersededAt, supersededByCorrection);
        }
    }
}
