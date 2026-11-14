package com.casino.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.persistence.Entity;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * Architecture rules of the modular monolith: module boundaries (ADR-0001), layered packages inside
 * each module (ADR-0004) and their enforcement with ArchUnit (ADR-0005). The rule ids R1–R12 match
 * {@code docs/plans/week-02.md} §9; R13–R15 match {@code docs/plans/week-03.md} §9.
 *
 * <p>The rules are parameterised by the root package, so {@code ArchitectureRulesTest} can prove
 * against a small fixture that every rule actually detects its violation.
 */
final class ArchitectureRules {

    /** Business modules: the direct sub-packages of the root, except {@code common}. */
    static final List<String> BUSINESS_MODULES =
            List.of("auth", "user", "wallet", "game", "learning", "responsibleplay", "admin");

    /** Layer packages inside a module (ADR-0004). */
    static final List<String> LAYERS =
            List.of("controller", "service", "repository", "entity", "dto", "config", "exception");

    /** Layers of a module that other modules may use: its public API. */
    static final List<String> PUBLIC_API_LAYERS = List.of("service", "dto", "exception");

    private static final String COMMON = "common";

    private final String root;

    ArchitectureRules(String root) {
        this.root = root;
    }

    /** R1: {@code common} is the shared kernel and must not depend on any business module. */
    ArchRule commonDoesNotDependOnBusinessModules() {
        return noClasses().that().resideInAPackage(module(COMMON))
                .should().dependOnClassesThat().resideInAnyPackage(businessModules())
                .as("R1 common does not depend on business modules");
    }

    /** R2: no dependency cycles between modules. */
    ArchRule modulesAreFreeOfCycles() {
        return slices().matching(root + ".(*)..").should().beFreeOfCycles()
                .as("R2 modules are free of cycles");
    }

    /** R3: a module uses another business module only through that module's public API. */
    ArchRule modulesAccessEachOtherOnlyThroughPublicApi() {
        List<ArchRule> rules = new ArrayList<>();
        for (String module : BUSINESS_MODULES) {
            String[] publicApi = PUBLIC_API_LAYERS.stream().map(layer -> layer(module, layer)).toArray(String[]::new);
            rules.add(noClasses().that().resideOutsideOfPackage(module(module))
                    .should().dependOnClassesThat(resideInAPackage(module(module)).and(not(resideInAnyPackage(publicApi))))
                    .as("R3 other modules use '" + module + "' only through its " + PUBLIC_API_LAYERS + " packages"));
        }
        return CompositeArchRule.of(rules);
    }

    /**
     * R4: layer direction {@code controller → service → repository → entity}. Calls within a layer are
     * allowed; {@code dto}, {@code config} and {@code exception} are not part of the chain.
     */
    ArchRule layersAreRespected() {
        return layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Controller").definedBy(root + "..controller..")
                .layer("Service").definedBy(root + "..service..")
                .layer("Repository").definedBy(root + "..repository..")
                // No module has entities yet (users follow in W2-05).
                .optionalLayer("Entity").definedBy(root + "..entity..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
                .whereLayer("Entity").mayOnlyBeAccessedByLayers("Repository", "Service")
                .as("R4 layers controller → service → repository → entity are respected");
    }

    /** R5: Spring and JPA stereotypes live in their layer package. */
    ArchRule stereotypesResideInTheirLayer() {
        return CompositeArchRule
                .of(classes().that().areMetaAnnotatedWith(Controller.class)
                        .should().resideInAPackage(root + "..controller.."))
                .and(classes().that().areMetaAnnotatedWith(ControllerAdvice.class)
                        .should().resideInAPackage(root + "..exception.."))
                .and(classes().that().areAnnotatedWith(Entity.class)
                        .should().resideInAPackage(root + "..entity..")
                        .allowEmptyShould(true))
                .and(classes().that().areAnnotatedWith(Repository.class)
                        .or().areAssignableTo(org.springframework.data.repository.Repository.class)
                        .should().resideInAPackage(root + "..repository.."))
                .and(classes().that().areMetaAnnotatedWith(Configuration.class)
                        .and().areNotAnnotatedWith(SpringBootApplication.class)
                        .should().resideInAPackage(root + "..config.."))
                .as("R5 controllers, controller advice, entities, repositories and configuration reside in their layer");
    }

    /** R6: controllers work with DTOs only. */
    ArchRule controllersDoNotUseRepositoriesOrEntities() {
        return noClasses().that().resideInAPackage(root + "..controller..")
                .should().dependOnClassesThat().resideInAnyPackage(root + "..repository..", root + "..entity..")
                .orShould().dependOnClassesThat().areAnnotatedWith(Entity.class)
                .as("R6 controllers do not use repositories or entities");
    }

    /** R7: DTOs never expose entities. Enums from {@code entity} packages are allowed. */
    ArchRule dtosDoNotDependOnEntities() {
        return noClasses().that().resideInAPackage(root + "..dto..")
                .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
                .as("R7 DTOs do not depend on @Entity classes");
    }

    /** R8: the type carrying the password hash is visible only to {@code user} and {@code auth}. */
    ArchRule userCredentialsAreUsedOnlyByUserAndAuth() {
        return noClasses().that().resideOutsideOfPackages(module("user"), module("auth"))
                .should().dependOnClassesThat().haveFullyQualifiedName(root + ".user.dto.UserCredentials")
                .as("R8 user.dto.UserCredentials is used only by the user and auth modules");
    }

    /** R9: the Spring Security configuration API is used only by {@code auth.config}. */
    ArchRule securityConfigurationOnlyInAuthConfig() {
        return noClasses().that().resideOutsideOfPackage(layer("auth", "config"))
                .should().dependOnClassesThat().resideInAPackage("org.springframework.security.config..")
                .as("R9 the Spring Security configuration API is used only in auth.config");
    }

    /**
     * R13: only the modules that may change balances use {@code wallet.service.WalletService}: the wallet
     * itself and, from their weeks on, {@code game}, {@code learning} and {@code admin}
     * ({@code docs/plans/week-03.md} §9). The API has no credit or debit operation of its own.
     */
    ArchRule walletServiceIsUsedOnlyByModulesThatChangeBalances() {
        return noClasses().that().resideOutsideOfPackages(module("wallet"), module("game"), module("learning"), module("admin"))
                .should().dependOnClassesThat().haveFullyQualifiedName(root + ".wallet.service.WalletService")
                .as("R13 wallet.service.WalletService is used only by the wallet, game, learning and admin modules");
    }

    /**
     * R14: {@code wallet} depends on {@code user} only through {@code user.dto} (the {@code UserRegistered}
     * event), never through {@code user.service}, {@code user.exception} or internals, so wallet logic
     * cannot reach account management ({@code docs/plans/week-03.md} §9). Narrower than R3 for this pair;
     * the opposite direction ({@code user → wallet}) would be a cycle (R2).
     */
    ArchRule walletUsesUserOnlyThroughDtos() {
        return noClasses().that().resideInAPackage(module("wallet"))
                .should().dependOnClassesThat(resideInAPackage(module("user")).and(not(resideInAPackage(layer("user", "dto")))))
                .as("R14 wallet depends on user only through user.dto");
    }

    /**
     * R15: the test-only ledger types ({@code TEST_CREDIT}, {@code TEST_DEBIT}) are never used by
     * production code. Test databases allow them (a test-only Flyway callback), the production schema
     * rejects them, so a production use would pass the tests and fail only in production.
     */
    ArchRule testLedgerTypesAreNotUsedByProductionCode() {
        String dtoType = root + ".wallet.dto.WalletTransactionType";
        String entityType = root + ".wallet.entity.TransactionType";
        return noClasses().that().doNotHaveFullyQualifiedName(dtoType).and().doNotHaveFullyQualifiedName(entityType)
                .should().accessField(dtoType, "TEST_CREDIT").orShould().accessField(dtoType, "TEST_DEBIT")
                .orShould().accessField(entityType, "TEST_CREDIT").orShould().accessField(entityType, "TEST_DEBIT")
                .as("R15 the test-only ledger types TEST_CREDIT and TEST_DEBIT are not used by production code");
    }

    /** R10: dependencies are injected through constructors. */
    ArchRule noFieldInjection() {
        return GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION
                .as("R10 no field injection");
    }

    /** R11: every direct sub-package of the root is a known module, so all rules above cover it. */
    ArchRule onlyKnownModules() {
        List<String> modules = new ArrayList<>(List.of(module(COMMON)));
        modules.addAll(List.of(businessModules()));
        return classes().that().resideInAPackage(root + "..").and().resideOutsideOfPackage(root)
                .should().resideInAnyPackage(modules.toArray(String[]::new))
                .as("R11 every top-level package is a known module (" + COMMON + ", " + BUSINESS_MODULES + ")")
                .because("a new module must be added to ArchitectureRules.BUSINESS_MODULES");
    }

    /**
     * R12: every class lives in a layer package. Business modules use
     * {@code <module>.<layer>}; {@code common} uses {@code common.config}, {@code common.exception}
     * or {@code common.<feature>.<layer>} (e.g. {@code common.system.controller}).
     */
    ArchRule classesResideInLayerPackages() {
        List<String> packages = new ArrayList<>();
        for (String module : BUSINESS_MODULES) {
            LAYERS.forEach(layer -> packages.add(layer(module, layer)));
        }
        packages.add(layer(COMMON, "config"));
        packages.add(layer(COMMON, "exception"));
        LAYERS.forEach(layer -> packages.add(root + "." + COMMON + ".*." + layer + ".."));
        return classes().that().resideInAPackage(root + "..").and().resideOutsideOfPackage(root)
                .and().doNotHaveSimpleName("package-info")
                .should().resideInAnyPackage(packages.toArray(String[]::new))
                .as("R12 classes reside in a layer package " + LAYERS);
    }

    private String[] businessModules() {
        return BUSINESS_MODULES.stream().map(this::module).toArray(String[]::new);
    }

    private String module(String module) {
        return root + "." + module + "..";
    }

    private String layer(String module, String layer) {
        return root + "." + module + "." + layer + "..";
    }
}
