package com.agentic.orchestrator.approval;

import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.FileAction;
import com.agentic.orchestrator.artifact.Artifacts.IntendedFile;
import com.agentic.orchestrator.policy.PolicyEngine;
import com.agentic.orchestrator.policy.ProposedChange;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic: decides which outputs need a human. The LLM never gets a say in what counts as high impact.
 */
@Component
public class HighImpactClassifier {

    private static final Pattern MIGRATION = Pattern.compile("^src/main/resources/db/migration/.*\\.sql$");
    private static final Pattern API_ANNOTATION = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping");

    private final List<Pattern> securityPatterns;
    private final int largeDiffLines;

    public HighImpactClassifier(PolicyEngine policy) {
        this.securityPatterns = policy.config().securitySensitivePathPatterns().stream().map(Pattern::compile).toList();
        this.largeDiffLines = policy.config().largeDiffLines();
    }

    /** Classifies the actual content of a patch. */
    public Set<ImpactReason> classify(List<ProposedChange> changes) {
        Set<ImpactReason> reasons = EnumSet.noneOf(ImpactReason.class);
        int changedLines = 0;
        for (ProposedChange c : changes) {
            String path = c.path();
            if (MIGRATION.matcher(path).matches() && c.isNewFile()) {
                reasons.add(ImpactReason.SCHEMA_CHANGE);
            }
            if (path.equals("pom.xml")) {
                reasons.add(ImpactReason.DEPENDENCY_CHANGE);
            }
            if (c.isDelete()) {
                reasons.add(ImpactReason.FILE_DELETION);
            }
            if (isSecuritySensitive(path)) {
                reasons.add(ImpactReason.SECURITY_SENSITIVE_FILE);
            }
            if (path.endsWith(".java") && !isTest(path)
                    && (touchesApi(c.addedLines()) || touchesApi(c.removedLines()))) {
                reasons.add(ImpactReason.API_CHANGE);
            }
            changedLines += c.addedLines().size() + c.removedLines().size();
        }
        if (changedLines > largeDiffLines) {
            reasons.add(ImpactReason.LARGE_DIFF);
        }
        return reasons;
    }

    /** Classifies a design before any code exists, from the files it intends to touch. */
    public Set<ImpactReason> classify(DesignDoc design) {
        Set<ImpactReason> reasons = EnumSet.noneOf(ImpactReason.class);
        for (IntendedFile file : design.intendedFiles()) {
            String path = file.path().replace('\\', '/');
            if (MIGRATION.matcher(path).matches() && file.action() == FileAction.CREATE) {
                reasons.add(ImpactReason.SCHEMA_CHANGE);
            }
            if (path.equals("pom.xml")) {
                reasons.add(ImpactReason.DEPENDENCY_CHANGE);
            }
            if (file.action() == FileAction.DELETE) {
                reasons.add(ImpactReason.FILE_DELETION);
            }
            if (isSecuritySensitive(path)) {
                reasons.add(ImpactReason.SECURITY_SENSITIVE_FILE);
            }
            if (path.endsWith("Controller.java")) {
                reasons.add(ImpactReason.API_CHANGE);
            }
        }
        if (design.newDependencies() != null && !design.newDependencies().isEmpty()) {
            reasons.add(ImpactReason.DEPENDENCY_CHANGE);
        }
        return reasons;
    }

    /** Tests cannot change runtime behaviour, so a test about the rate limiter is not itself security-relevant. */
    private boolean isSecuritySensitive(String path) {
        return !isTest(path) && securityPatterns.stream().anyMatch(p -> p.matcher(path).find());
    }

    private static boolean isTest(String path) {
        return path.startsWith("src/test/");
    }

    private static boolean touchesApi(List<String> lines) {
        return lines.stream().anyMatch(l -> API_ANNOTATION.matcher(l).find());
    }
}
