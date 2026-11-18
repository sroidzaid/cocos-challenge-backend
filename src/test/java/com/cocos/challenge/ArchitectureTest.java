package com.cocos.challenge;

import java.util.List;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Executable architecture rules. They protect the hexagonal layering and the module boundaries as the
 * code base grows. Rules are allowed to match no classes while modules are still empty.
 */
@AnalyzeClasses(packages = "com.cocos.challenge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.cocos.challenge";
    private static final List<String> MODULES = List.of("instruments", "marketdata", "orders", "portfolio", "users");

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..", "..infrastructure..")
            .allowEmptyShould(true)
            .because("the domain must not depend on frameworks or infrastructure details");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_application = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..application..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule application_does_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true)
            .because("use cases talk to infrastructure only through ports");

    /**
     * A module may only use another module through its inbound ports and the domain types they expose;
     * use case implementations, outbound ports and adapters are private to their module.
     */
    @ArchTest
    static final ArchRule modules_interact_only_through_inbound_ports = CompositeArchRule.of(
            MODULES.stream()
                    .map(module -> noClasses()
                            .that().resideOutsideOfPackage(ROOT + "." + module + "..")
                            .should().dependOnClassesThat().resideInAnyPackage(
                                    ROOT + "." + module + ".application",
                                    ROOT + "." + module + ".application.port.out..",
                                    ROOT + "." + module + ".infrastructure..")
                            .allowEmptyShould(true))
                    .map(ArchRule.class::cast)
                    .toList());

    @ArchTest
    static final ArchRule modules_are_free_of_cycles = slices()
            .matching(ROOT + ".(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);
}
