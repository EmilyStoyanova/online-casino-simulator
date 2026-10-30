package com.casino.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import archfixture.auth.service.OtherModuleRepositoryAccess;
import archfixture.common.exception.CommonDependsOnModule;
import archfixture.game.service.CredentialsOutsideAuth;
import archfixture.game.service.GameCycle;
import archfixture.unknownmodule.service.UnknownModule;
import archfixture.user.controller.EntityReturningController;
import archfixture.user.dto.EntityWrappingDto;
import archfixture.user.repository.ReverseLayerAccess;
import archfixture.user.service.FieldInjection;
import archfixture.user.service.MisplacedController;
import archfixture.user.util.OutsideLayerPackage;
import archfixture.wallet.service.WalletCycle;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * Proves that every architecture rule detects its violation, so a green {@link ModuleArchitectureTest}
 * means something. The fixture in package {@code archfixture} contains valid classes and, per rule,
 * the classes that violate it. Each rule must be violated by exactly those classes.
 *
 * <p>R9 (Spring Security configuration API) has no fixture, because Spring Security is not on the
 * classpath until W2-04.
 */
class ArchitectureRulesTest {

    private static final JavaClasses FIXTURE = new ClassFileImporter().importPackages("archfixture");
    private static final ArchitectureRules RULES = new ArchitectureRules("archfixture");

    @Test
    void r1DetectsCommonDependingOnABusinessModule() {
        assertViolatedOnlyBy(RULES.commonDoesNotDependOnBusinessModules(), CommonDependsOnModule.class);
    }

    @Test
    void r2DetectsCyclesBetweenModules() {
        assertViolatedOnlyBy(RULES.modulesAreFreeOfCycles(), WalletCycle.class, GameCycle.class);
    }

    @Test
    void r3DetectsAccessToAnotherModulesInternals() {
        assertViolatedOnlyBy(RULES.modulesAccessEachOtherOnlyThroughPublicApi(), OtherModuleRepositoryAccess.class);
    }

    @Test
    void r4DetectsReverseAndSkippedLayerAccess() {
        assertViolatedOnlyBy(RULES.layersAreRespected(), ReverseLayerAccess.class, EntityReturningController.class);
    }

    @Test
    void r5DetectsMisplacedStereotypes() {
        assertViolatedOnlyBy(RULES.stereotypesResideInTheirLayer(), MisplacedController.class);
    }

    @Test
    void r6DetectsControllersUsingEntities() {
        assertViolatedOnlyBy(RULES.controllersDoNotUseRepositoriesOrEntities(), EntityReturningController.class);
    }

    @Test
    void r7DetectsDtosWrappingEntitiesButAllowsEntityEnums() {
        assertViolatedOnlyBy(RULES.dtosDoNotDependOnEntities(), EntityWrappingDto.class);
    }

    @Test
    void r8DetectsCredentialsOutsideUserAndAuth() {
        assertViolatedOnlyBy(RULES.userCredentialsAreUsedOnlyByUserAndAuth(), CredentialsOutsideAuth.class);
    }

    @Test
    void r10DetectsFieldInjection() {
        assertViolatedOnlyBy(RULES.noFieldInjection(), FieldInjection.class);
    }

    @Test
    void r11DetectsUnknownModules() {
        assertViolatedOnlyBy(RULES.onlyKnownModules(), UnknownModule.class);
    }

    @Test
    void r12DetectsClassesOutsideLayerPackages() {
        assertViolatedOnlyBy(RULES.classesResideInLayerPackages(), UnknownModule.class, OutsideLayerPackage.class);
    }

    /** The rule fails, and every reported violation names one of the expected classes. */
    private static void assertViolatedOnlyBy(ArchRule rule, Class<?>... offenders) {
        EvaluationResult result = rule.evaluate(FIXTURE);

        assertThat(result.hasViolation()).as("%s has violations", rule.getDescription()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .as("%s violations", rule.getDescription())
                .allSatisfy(detail -> assertThat(Arrays.stream(offenders).map(Class::getName))
                        .anyMatch(detail::contains));
        for (Class<?> offender : offenders) {
            assertThat(result.getFailureReport().getDetails())
                    .as("%s reports %s", rule.getDescription(), offender.getSimpleName())
                    .anyMatch(detail -> detail.contains(offender.getName()));
        }
    }
}
