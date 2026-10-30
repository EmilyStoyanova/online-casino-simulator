package com.casino.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the architecture rules (ADR-0005, {@code docs/plans/week-02.md} §9) on the production code.
 * Runs with the unit tests ({@code ./mvnw test}); every violation fails the build.
 */
@AnalyzeClasses(packages = "com.casino", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureTest {

    private static final ArchitectureRules RULES = new ArchitectureRules("com.casino");

    @ArchTest
    static final ArchRule commonDoesNotDependOnBusinessModules = RULES.commonDoesNotDependOnBusinessModules();

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles = RULES.modulesAreFreeOfCycles();

    @ArchTest
    static final ArchRule modulesAccessEachOtherOnlyThroughPublicApi = RULES.modulesAccessEachOtherOnlyThroughPublicApi();

    @ArchTest
    static final ArchRule layersAreRespected = RULES.layersAreRespected();

    @ArchTest
    static final ArchRule stereotypesResideInTheirLayer = RULES.stereotypesResideInTheirLayer();

    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesOrEntities = RULES.controllersDoNotUseRepositoriesOrEntities();

    @ArchTest
    static final ArchRule dtosDoNotDependOnEntities = RULES.dtosDoNotDependOnEntities();

    @ArchTest
    static final ArchRule userCredentialsAreUsedOnlyByUserAndAuth = RULES.userCredentialsAreUsedOnlyByUserAndAuth();

    @ArchTest
    static final ArchRule securityConfigurationOnlyInAuthConfig = RULES.securityConfigurationOnlyInAuthConfig();

    @ArchTest
    static final ArchRule noFieldInjection = RULES.noFieldInjection();

    @ArchTest
    static final ArchRule onlyKnownModules = RULES.onlyKnownModules();

    @ArchTest
    static final ArchRule classesResideInLayerPackages = RULES.classesResideInLayerPackages();
}
