package com.insurance.policy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import java.math.BigDecimal;

/**
 * 아키텍처 규칙.
 *
 * <p>이 테스트는 "좋은 구조를 설명"하지 않는다. <b>구조가 무너질 수 없게</b> 만든다.
 *
 * <p>규칙을 문서에만 두면 지켜지지 않는다. 컴파일이 되기 때문이다.
 * 여기 있는 규칙은 위반 시 빌드를 실패시킨다.
 *
 * @see docs/design/07-architecture.md §4
 */
// 주의: DoNotIncludeJars 를 쓰면 안 된다.
// 형제 모듈(policy-domain 등)은 bootstrap의 테스트 클래스패스에 JAR로 올라오므로
// 그 옵션을 켜면 검사 대상이 0개가 되고, 모든 규칙이 조용히 통과해 버린다.
// 아래 `검사_대상이_비어있지_않다` 가 이 상황을 잡는 안전장치다.
@AnalyzeClasses(
        packages = "com.insurance.policy",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    /**
     * 도메인은 프레임워크를 모른다.
     *
     * <p>1차 방어선은 Gradle이다 — policy-domain 의 컴파일 클래스패스에
     * Spring·JPA·Jackson이 아예 없어서 import 자체가 컴파일되지 않는다.
     * 이 규칙은 누군가 build.gradle에 의존성을 추가했을 때를 잡는 2차 방어선이다.
     *
     * <p>이 모듈에서 특히 중요한 이유: Bitemporal 시점 판정(Temporal.isEffectiveOn)이
     * 순수 함수여야 DB 없이 재현성을 검증할 수 있다.
     */
    @ArchTest
    static final ArchRule 도메인은_프레임워크를_모른다 =
            noClasses()
                    .that().resideInAPackage("..policy.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "jakarta.servlet..",
                            "com.fasterxml.jackson..",
                            "org.apache.kafka..")
                    .because("도메인은 순수 자바여야 한다. 영속성·전송 기술이 바뀌어도 "
                            + "비즈니스 규칙은 그대로여야 하고, Spring 컨텍스트 없이 "
                            + "밀리초 단위로 테스트할 수 있어야 한다.");

    /**
     * ★ v1이 무너진 바로 그 지점.
     *
     * <p>{@code @Transactional} 안에서 publishEvent()를 호출하면 리스너가 커밋 전에 돈다.
     * Kafka 발행으로 바꾸는 순간 롤백된 트랜잭션의 이벤트가 외부로 나간다.
     *
     * <p>이 컨텍스트에서는 {@code policy.corrected}가 걸려 있어 더 위험하다.
     * 정정이 롤백됐는데 이벤트만 나가면, claims가 멀쩡한 청구를 재심사 큐에 쌓는다.
     * 반대로 이벤트가 유실되면 claims는 틀린 근거로 계속 지급한다.
     */
    @ArchTest
    static final ArchRule 애플리케이션은_스프링_이벤트퍼블리셔를_쓰지_않는다 =
            noClasses()
                    .that().resideInAPackage("..policy.application..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
                    .because("도메인 이벤트는 애그리거트가 record()하고 OutboxAppender로 "
                            + "같은 트랜잭션에 저장한다. 커밋 전 외부 발행은 롤백해도 되돌릴 수 없다.");

    /** 애플리케이션 계층은 영속성·전송 기술을 모른다. 포트로만 바깥과 통신한다. */
    @ArchTest
    static final ArchRule 애플리케이션은_기술_상세를_모른다 =
            noClasses()
                    .that().resideInAPackage("..policy.application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.springframework.web..",
                            "org.apache.kafka..")
                    .because("유스케이스는 포트를 통해서만 바깥과 통신한다.");

    /**
     * 심사 룰은 순수 함수여야 한다. 프레임워크가 끼면 I/O가 파이프라인 안으로 새어든다.
     *
     * <p>{@code allowEmptyShould(true)}: policy-underwriting는 Phase 5에서 채워진다.
     * Phase 0에서는 비어 있는 것이 정상이므로 규칙이 대상 없음으로 실패하지 않게 한다.
     * 오타로 인한 vacuous pass는 아래 {@code 검사_대상이_비어있지_않다}가 막는다.
     */
    @ArchTest
    static final ArchRule 언더라이팅_엔진은_프레임워크를_모른다 =
            noClasses()
                    .that().resideInAPackage("..policy.underwriting..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
                    .because("룰은 (입력) → (판정, 근거) 순수 함수다. I/O는 "
                            + "AdjudicationContext로 미리 적재된다.")
                    .allowEmptyShould(true);

    /**
     * 의존은 안쪽으로만 흐른다.
     *
     * <p>{@code withOptionalLayers(true)}: Underwriting 계층은 Phase 5에서 채워진다.
     * 아직 비어 있다는 이유로 이 규칙 전체가 실패하면, 정작 검사해야 할
     * 나머지 계층의 위반을 못 보게 된다.
     */
    @ArchTest
    static final ArchRule 의존은_안쪽으로만_흐른다 =
            Architectures.layeredArchitecture()
                    .consideringOnlyDependenciesInAnyPackage("com.insurance.policy..")
                    .withOptionalLayers(true)
                    .layer("Domain").definedBy("..policy.domain..")
                    .layer("Underwriting").definedBy("..policy.underwriting..")
                    .layer("Application").definedBy("..policy.application..")
                    .layer("Adapter").definedBy("..policy.adapter..")
                    .layer("Bootstrap").definedBy("com.insurance.policy", "..policy.config..")

                    .whereLayer("Bootstrap").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Adapter").mayOnlyBeAccessedByLayers("Bootstrap", "Adapter")
                    .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter", "Bootstrap")
                    .whereLayer("Underwriting").mayOnlyBeAccessedByLayers("Application", "Bootstrap")
                    .whereLayer("Domain")
                    .mayOnlyBeAccessedByLayers("Application", "Underwriting", "Adapter", "Bootstrap")

                    .because("의존은 안쪽(도메인)으로만 흐른다.");

    /**
     * 금액은 Money VO로만 표현한다.
     *
     * <p>원화에 소수점은 없다. BigDecimal scale=2 모델은 "1원 미만이 존재한다"는
     * 잘못된 전제를 코드에 새긴다. 금액 계산은 절사 시점과 방향이 한 곳에 모여 있어야 한다.
     */
    @ArchTest
    static final ArchRule 도메인_금액은_Money로만 =
            noFields()
                    .that().areDeclaredInClassesThat().resideInAPackage("..policy.domain..")
                    .should().haveRawType(BigDecimal.class)
                    .because("금액은 Money(원 단위 정수)로 표현한다. "
                            + "비율(coinsuranceRate)처럼 소수가 필요한 값만 "
                            + "BigDecimal을 메서드 인자로 받는다.");

    /** 로깅 프레임워크를 직접 부르지 않는다 — 도메인에는 로깅할 이유가 없다. */
    @ArchTest
    static final ArchRule 도메인은_로깅하지_않는다 =
            noClasses()
                    .that().resideInAPackage("..policy.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.slf4j..", "ch.qos.logback..", "java.util.logging..")
                    .because("도메인은 판정 결과를 반환할 뿐 부수효과를 내지 않는다. "
                            + "민감정보가 로그로 새는 경로를 원천 차단하는 효과도 있다.");

    /** 표준 출력은 로깅이 아니다. 운영에서 수집되지 않고 민감정보가 샐 수 있다. */
    @ArchTest
    static final ArchRule 표준출력을_쓰지_않는다 =
            com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    /**
     * 낡은 날짜 API 금지.
     *
     * <p>{@code java.util.Date}는 가변이고 타임존 처리가 모호하다. 유효기간 경계를
     * 하루만 잘못 잡아도 시점 조회 결과가 뒤집히는 이 시스템에서는 치명적이다.
     */
    @ArchTest
    static final ArchRule 낡은_날짜_API를_쓰지_않는다 =
            noClasses()
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Date")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("java.util.Calendar")
                    .because("java.time을 쓴다.");

    @ArchTest
    static final ArchRule 조다타임을_쓰지_않는다 =
            com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;


    // ─────────────────────────────────────────────────────────────────────
    // 이 저장소 고유 규칙 — Bitemporal 이력의 불변성
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 시간축을 가진 사실(Temporal)에는 세터를 두지 않는다.
     *
     * <p>이력 레코드를 수정하면 과거가 바뀐다. 그러면 claims가 4월에 내린 부지급 판단을
     * 6월에 재현할 수 없고, 이 시스템의 존재 이유가 사라진다.
     *
     * <p>변경은 새 행, 정정은 {@code supersededAt} 마킹 + 새 행이다.
     * DB 트리거도 같은 규칙을 강제하지만, 코드에서 먼저 막는 편이 실수를 일찍 잡는다.
     */
    @ArchTest
    static final ArchRule 이력_엔티티에_세터가_없다 =
            noMethods()
                    .that().areDeclaredInClassesThat()
                    .implement("com.insurance.policy.domain.shared.Temporal")
                    .should().haveNameMatching("set[A-Z].*")
                    .because("Bitemporal 이력은 수정하지 않는다. 변경은 새 행으로 표현한다.")
                    .allowEmptyShould(true);

    /**
     * 시간축을 가진 사실의 필드는 final이어야 한다.
     *
     * <p>세터가 없어도 패키지 내부에서 필드를 직접 바꾸면 같은 문제가 생긴다.
     */
    @ArchTest
    static final ArchRule 이력_엔티티의_필드는_불변이다 =
            fields()
                    .that().areDeclaredInClassesThat()
                    .implement("com.insurance.policy.domain.shared.Temporal")
                    .and().areNotStatic()
                    .should().beFinal()
                    .because("과거 사실은 바뀌지 않는다. 정정도 새 레코드로 표현한다.")
                    .allowEmptyShould(true);

    /**
     * 검사 대상이 실제로 로드되는지 확인하는 안전장치.
     *
     * <p>패키지 경로를 잘못 쓰거나 import 옵션이 모듈 JAR을 걸러내면 분석 대상이 0개가 되고,
     * 위의 모든 규칙이 <b>조용히 통과</b>한다. 규칙이 통과하는 것과 검사할 대상이 없는 것은 다르다.
     *
     * <p>claims-platform에서 실제로 이 테스트가 {@code ImportOption.DoNotIncludeJars}
     * 때문에 형제 모듈이 하나도 로드되지 않던 상황을 잡아냈다.
     *
     * <p>Phase가 진행되면 아직 비어 있는 모듈을 이 목록에서 빼서 검증을 넓힌다.
     */
    @ArchTest
    static void 검사_대상이_비어있지_않다(JavaClasses classes) {
        // Phase 0에서 코드가 있어야 하는 패키지
        assertPackagePopulated(classes, "com.insurance.policy.domain");
        assertPackagePopulated(classes, "com.insurance.policy.application");
        assertPackagePopulated(classes, "com.insurance.policy.adapter.persistence");

        // Phase 1에서 채워진다: com.insurance.policy.adapter.web / .messaging
        // Phase 5에서 채워진다: com.insurance.policy.underwriting / .adapter.external
    }

    private static void assertPackagePopulated(JavaClasses classes, String packageName) {
        long count = classes.stream()
                .filter(c -> c.getPackageName().startsWith(packageName))
                .filter(c -> !c.getSimpleName().isEmpty())      // package-info 제외
                .count();
        if (count == 0) {
            throw new AssertionError(
                    "'%s' 패키지의 클래스가 하나도 로드되지 않았습니다. @AnalyzeClasses의 패키지 경로와 "
                            + "importOptions를 확인하세요. 대상이 없으면 모든 규칙이 무의미하게 통과합니다."
                            .formatted(packageName));
        }
    }
}
