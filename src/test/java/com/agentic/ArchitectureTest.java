package com.agentic;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The structure the design relies on, enforced: the two functionalities are independent, and the only code that can start
 * a process is the sandboxed tool layer (agents get no generic "exec").
 */
@AnalyzeClasses(packages = "com.agentic", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule shortenerDoesNotDependOnTheOrchestrator = noClasses().that().resideInAPackage("com.agentic.shortener..")
            .should().dependOnClassesThat().resideInAPackage("com.agentic.orchestrator..")
            .because("the orchestrator works on the shortener's source, never through its runtime");

    @ArchTest
    static final ArchRule orchestratorDoesNotDependOnTheShortener = noClasses().that().resideInAPackage("com.agentic.orchestrator..")
            .should().dependOnClassesThat().resideInAPackage("com.agentic.shortener..")
            .because("the engine must stay independent of the code it changes");

    @ArchTest
    static final ArchRule onlyTheToolLayerStartsProcesses = noClasses()
            .that().resideOutsideOfPackage("com.agentic.orchestrator.tool..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
            .orShould().callMethod(Runtime.class, "exec", String.class)
            .orShould().callMethod(Runtime.class, "exec", String[].class)
            .because("agents must not be able to run arbitrary commands; GitTool and the build runner are the only exceptions");

    @ArchTest
    static final ArchRule gatesAreIndependentOfTheEngine = noClasses().that().resideInAPackage("com.agentic.orchestrator.gate..")
            .should().dependOnClassesThat().resideInAPackage("com.agentic.orchestrator.engine..")
            .because("gates are small pure checks that the engine calls, not the other way round");

    @ArchTest
    static final ArchRule policyIsIndependentOfTheEngineAndAgents = noClasses().that().resideInAPackage("com.agentic.orchestrator.policy..")
            .should().dependOnClassesThat().resideInAnyPackage("com.agentic.orchestrator.engine..", "com.agentic.orchestrator.agent..")
            .because("policy rules must be usable and testable on their own");

    @ArchTest
    static final ArchRule agentsDoNotDriveTheEngine = classes().that().resideInAPackage("com.agentic.orchestrator.agent..")
            .should().onlyDependOnClassesThat().resideOutsideOfPackage("com.agentic.orchestrator.engine..")
            .because("agents propose output; only the engine decides what happens to it");
}
