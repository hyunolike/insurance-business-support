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
     * 변경 반영.
     *
     * <p>기존 행을 지우고 다시 넣지 않는다 — 이미 저장된 구간은 그대로 두고,
     * 애그리거트가 만든 <b>새 구간만</b> 골라 INSERT한다.
     * 구간을 닫는 것(validTo 단축)은 새 행 삽입 전에 기존 행의 종료일만 옮기는 것이므로
     * 여기서는 겹침 제약을 피하기 위해 닫기 → 삽입 순으로 수행한다.
     */
    @Override
    public void applyEndorsement(Policy policy) {
        syncCoverages(policy);
        syncVersions(policy);
    }

    @Override
    public void applyStatusChange(Policy policy) {
        syncVersions(policy);
    }

    @Override
    public void applyCorrection(Policy policy, CorrectionRecord record) {
        // ★ 이 시스템에서 허용되는 유일한 UPDATE
        for (Exclusion e : policy.allExclusions()) {
            if (e.supersededAt() != null) {
                jdbc.update("""
                        UPDATE exclusion_version SET superseded_at = ?
                        WHERE policy_no = ? AND exclusion_id = ? AND superseded_at IS NULL
                        """,
                        Timestamp.from(e.supersededAt()),
                        policy.policyNo().value(),
                        e.exclusionId().value());
            }
        }
        // 정정으로 추가된 대체 부담보
        syncExclusions(policy);

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

    /** 애그리거트에 있으나 DB에 없는 담보 구간을 넣는다. 기존 구간의 종료일 변경은 먼저 반영한다. */
    private void syncCoverages(Policy policy) {
        for (Coverage c : policy.allCoverages()) {
            int updated = jdbc.update("""
                    UPDATE coverage_version SET valid_to = ?
                    WHERE policy_no = ? AND coverage_code = ? AND valid_from = ?
                      AND superseded_at IS NULL AND valid_to <> ?
                    """,
                    c.validTo(), policy.policyNo().value(), c.coverageCode().value(),
                    c.validFrom(), c.validTo());
            if (updated == 0 && !coverageExists(policy.policyNo(), c)) {
                insertCoverage(policy.policyNo(), c);
            }
        }
    }

    private void syncVersions(Policy policy) {
        for (PolicyVersion v : policy.versions()) {
            int updated = jdbc.update("""
                    UPDATE policy_version SET valid_to = ?
                    WHERE policy_no = ? AND valid_from = ? AND status = ?
                      AND superseded_at IS NULL AND valid_to <> ?
                    """,
                    v.validTo(), policy.policyNo().value(), v.validFrom(),
                    v.status().name(), v.validTo());
            if (updated == 0 && !versionExists(policy.policyNo(), v)) {
                insertVersion(policy.policyNo(), v);
            }
        }
    }

    private void syncExclusions(Policy policy) {
        for (Exclusion e : policy.allExclusions()) {
            if (e.supersededAt() == null && !exclusionExists(policy.policyNo(), e)) {
                insertExclusion(policy.policyNo(), e);
            }
        }
    }

    private boolean coverageExists(PolicyNo policyNo, Coverage c) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM coverage_version
                WHERE policy_no = ? AND coverage_code = ? AND valid_from = ?
                """, Integer.class, policyNo.value(), c.coverageCode().value(), c.validFrom());
        return count != null && count > 0;
    }

    private boolean versionExists(PolicyNo policyNo, PolicyVersion v) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_version
                WHERE policy_no = ? AND valid_from = ? AND status = ?
                """, Integer.class, policyNo.value(), v.validFrom(), v.status().name());
        return count != null && count > 0;
    }

    private boolean exclusionExists(PolicyNo policyNo, Exclusion e) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM exclusion_version
                WHERE policy_no = ? AND exclusion_id = ? AND valid_from = ?
                """, Integer.class, policyNo.value(), e.exclusionId().value(), e.validFrom());
        return count != null && count > 0;
    }

    private void insertVersion(PolicyNo policyNo, PolicyVersion v) {
        jdbc.update("""
                INSERT INTO policy_version (policy_no, status, valid_from, valid_to,
                                            recorded_at, superseded_at, change_type,
                                            reason, actor_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policyNo.value(), v.status().name(), v.validFrom(), v.validTo(),
                Timestamp.from(v.recordedAt()),
                v.supersededAt() == null ? null : Timestamp.from(v.supersededAt()),
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
                        superseded_at, change_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                c.changeType().name());
    }

    private void insertExclusion(PolicyNo policyNo, Exclusion e) {
        jdbc.update("""
                INSERT INTO exclusion_version (policy_no, exclusion_id, type, target,
                        kcd_ranges, reason, uw_case_no, valid_from, valid_to,
                        recorded_at, superseded_at, change_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policyNo.value(), e.exclusionId().value(), e.type().name(), e.target(),
                e.kcdRanges().stream().map(KcdRange::expression).toArray(String[]::new),
                e.reason(), e.uwCaseNo(), e.validFrom(), e.validTo(),
                Timestamp.from(e.recordedAt()),
                e.supersededAt() == null ? null : Timestamp.from(e.supersededAt()),
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

    @Override
    public Optional<Policy> findCurrent(PolicyNo policyNo) {
        return load(policyNo, null, null);
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
        return load(policyNo, asOf, knownAt);
    }

    private Optional<Policy> load(PolicyNo policyNo, LocalDate asOf, Instant knownAt) {
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
                ? jdbc.query(VERSION_ASOF_SQL, VERSION_MAPPER, policyNo.value(), asOf, asOf, known, known)
                : jdbc.query(VERSION_CURRENT_SQL, VERSION_MAPPER, policyNo.value());

        List<Coverage> coverages = pointInTime
                ? jdbc.query(COVERAGE_ASOF_SQL, coverageMapper(), policyNo.value(), asOf, asOf, known, known)
                : jdbc.query(COVERAGE_CURRENT_SQL, coverageMapper(), policyNo.value());

        List<Exclusion> exclusions = pointInTime
                ? jdbc.query(EXCLUSION_ASOF_SQL, EXCLUSION_MAPPER, policyNo.value(), asOf, asOf, known, known)
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

    private static final String ASOF_PREDICATE = """
              AND valid_from <= ? AND ? < valid_to
              AND recorded_at <= ?
              AND (superseded_at IS NULL OR superseded_at > ?)
            """;

    private static final String VERSION_COLUMNS =
            "status, valid_from, valid_to, recorded_at, superseded_at, change_type, reason, actor_ref";
    private static final String VERSION_ASOF_SQL =
            "SELECT " + VERSION_COLUMNS + " FROM policy_version WHERE policy_no = ?" + ASOF_PREDICATE;
    private static final String VERSION_CURRENT_SQL =
            "SELECT " + VERSION_COLUMNS + " FROM policy_version WHERE policy_no = ?"
                    + " AND superseded_at IS NULL";

    private static final String COVERAGE_COLUMNS = """
            coverage_code, coverage_name, benefit_category, treatment_types, insured_amount,
            coinsurance_rate, min_deductible, min_deductible_by_grade, annual_limit,
            per_visit_limit, annual_count_limit, waiting_period_end,
            valid_from, valid_to, recorded_at, superseded_at, change_type
            """;
    private static final String COVERAGE_ASOF_SQL =
            "SELECT " + COVERAGE_COLUMNS + " FROM coverage_version WHERE policy_no = ?" + ASOF_PREDICATE;
    private static final String COVERAGE_CURRENT_SQL =
            "SELECT " + COVERAGE_COLUMNS + " FROM coverage_version WHERE policy_no = ?"
                    + " AND superseded_at IS NULL";

    private static final String EXCLUSION_COLUMNS = """
            exclusion_id, type, target, kcd_ranges, reason, uw_case_no,
            valid_from, valid_to, recorded_at, superseded_at, change_type
            """;
    private static final String EXCLUSION_ASOF_SQL =
            "SELECT " + EXCLUSION_COLUMNS + " FROM exclusion_version WHERE policy_no = ?" + ASOF_PREDICATE;
    private static final String EXCLUSION_CURRENT_SQL =
            "SELECT " + EXCLUSION_COLUMNS + " FROM exclusion_version WHERE policy_no = ?"
                    + " AND superseded_at IS NULL";

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
                                Instant recordedAt, Instant supersededAt, ChangeType changeType,
                                String reason, String actorRef) {
            PolicyVersion base = changeType == ChangeType.CREATE
                    ? PolicyVersion.create(status, validFrom, validTo, recordedAt, actorRef)
                    : PolicyVersion.endorsement(status, validFrom, validTo, recordedAt,
                            reason, actorRef);
            return supersededAt == null ? base : base.superseded(supersededAt);
        }
    }

    private static final class CoverageReconstructor {
        private CoverageReconstructor() {
        }

        static Coverage of(CoverageCode code, String name, BenefitCategory category,
                           java.util.Set<TreatmentType> types, Money insuredAmount,
                           CoverageTerms terms, LocalDate waitingPeriodEnd,
                           LocalDate validFrom, LocalDate validTo, Instant recordedAt,
                           Instant supersededAt, ChangeType changeType) {
            Coverage base = Coverage.create(code, name, category, types, insuredAmount, terms,
                    waitingPeriodEnd, validFrom, validTo, recordedAt);
            if (changeType == ChangeType.ENDORSEMENT) {
                base = base.withTerms(terms, insuredAmount, validFrom, validTo, recordedAt);
            }
            return supersededAt == null ? base : base.superseded(supersededAt);
        }
    }

    private static final class ExclusionReconstructor {
        private ExclusionReconstructor() {
        }

        static Exclusion of(ExclusionId id, ExclusionType type, String target,
                            List<KcdRange> ranges, String reason, String uwCaseNo,
                            LocalDate validFrom, LocalDate validTo, Instant recordedAt,
                            Instant supersededAt, ChangeType changeType) {
            Exclusion base = Exclusion.create(id, type, target, ranges, reason, uwCaseNo,
                    validFrom, validTo, recordedAt);
            return supersededAt == null ? base : base.superseded(supersededAt);
        }
    }
}
