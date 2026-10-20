package com.agentic.orchestrator.artifact;

import java.util.List;

/** Typed, schema-validated outputs exchanged between stages. */
public final class Artifacts {

    private Artifacts() {
    }

    public static final String REQUIREMENT_SPEC = "RequirementSpec";
    public static final String IMPACT_REPORT = "ImpactReport";
    public static final String DESIGN_DOC = "DesignDoc";
    public static final String TASK_GRAPH = "TaskGraph";
    public static final String PATCH = "Patch";
    public static final String TEST_REPORT = "TestReport";
    public static final String RELEASE_SUMMARY = "ReleaseSummary";
    public static final String RELEASE_INFO = "ReleaseInfo";

    public enum ChangeType { GREENFIELD, BROWNFIELD }

    public record Ambiguity(String id, String question, String proposedAssumption) {
    }

    public record RequirementSpec(String title, ChangeType changeType, List<String> goals,
                                  List<String> functionalRequirements, List<String> nonFunctionalRequirements,
                                  List<String> acceptanceCriteria, List<Ambiguity> ambiguities,
                                  List<String> assumptions, double ambiguityScore) {
    }

    public record ImpactReport(String summary, List<String> affectedClasses, List<String> endpoints,
                               List<String> tables, List<String> dataFlows, List<String> risks,
                               String suspectedRootCause) {
    }

    public enum FileAction { CREATE, MODIFY, DELETE }

    public record IntendedFile(String path, FileAction action, String purpose) {
    }

    public record DesignDecision(String title, String rationale) {
    }

    public record DesignDoc(String summary, List<DesignDecision> decisions, List<IntendedFile> intendedFiles,
                            List<String> newDependencies, List<String> risks) {
    }

    public record Task(String id, String title, List<String> files, List<String> dependsOn,
                       List<String> acceptance) {
    }

    public record TaskGraph(List<Task> tasks) {
    }

    public enum ChangeOp { WRITE, REPLACE, DELETE }

    /**
     * One edit to the workspace. WRITE creates or overwrites a file; REPLACE swaps one exact, unique snippet
     * ({@code find} -> {@code replacement}); DELETE removes the file.
     */
    public record FileChange(String path, ChangeOp op, String content, String find, String replacement) {
    }

    public record Patch(String summary, List<FileChange> changes) {
    }

    public record TestReport(boolean compiled, int testsRun, int testsFailed, double lineCoverage,
                             long durationMillis, List<String> failures, String outputTail,
                             List<String> policyFindings) {

        public boolean passed(double minLineCoverage) {
            return compiled && testsFailed == 0 && testsRun > 0 && lineCoverage >= minLineCoverage
                    && policyFindings.isEmpty();
        }
    }

    public record ChecklistItem(String item, boolean ok, String detail) {
    }

    public record ReleaseSummary(String markdown, String goNoGo, List<String> risks, List<ChecklistItem> checklist) {
    }

    public record ReleaseInfo(String branch, String commit, String workspacePath, String summaryPath) {
    }
}
