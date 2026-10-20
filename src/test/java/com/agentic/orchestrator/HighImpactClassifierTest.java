package com.agentic.orchestrator;

import com.agentic.orchestrator.approval.HighImpactClassifier;
import com.agentic.orchestrator.approval.ImpactReason;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.FileAction;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.artifact.Artifacts.IntendedFile;
import com.agentic.orchestrator.policy.PolicyConfig;
import com.agentic.orchestrator.policy.PolicyEngine;
import com.agentic.orchestrator.policy.ProposedChange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HighImpactClassifierTest {

    private final HighImpactClassifier classifier = new HighImpactClassifier(new PolicyEngine(new PolicyConfig(
            List.of(), List.of(), 25, 1500, 50, List.of("(?i)security", "(?i)filter", "(?i)validator"))));

    private static ProposedChange change(String path, String before, String after) {
        return new ProposedChange(new FileChange(path, after == null ? ChangeOp.DELETE : ChangeOp.WRITE, after, null, null),
                before, after);
    }

    @Test
    void aNewMigrationIsASchemaChangeButEditingTheCodeAroundItIsNot() {
        assertThat(classifier.classify(List.of(change("src/main/resources/db/migration/V3__x.sql", null, "create table a();"))))
                .containsExactly(ImpactReason.SCHEMA_CHANGE);
        assertThat(classifier.classify(List.of(change("src/main/java/com/agentic/shortener/service/Foo.java", "a", "b"))))
                .isEmpty();
    }

    @Test
    void touchingThePomIsADependencyChange() {
        assertThat(classifier.classify(List.of(change("pom.xml", "<a/>", "<a/><b/>")))).containsExactly(ImpactReason.DEPENDENCY_CHANGE);
    }

    @Test
    void addingOrRemovingAnEndpointIsAnApiChangeButChangingAMethodBodyIsNot() {
        String path = "src/main/java/com/agentic/shortener/web/UrlController.java";
        String before = "@GetMapping(\"/a\")\npublic String a() { return \"x\"; }";
        assertThat(classifier.classify(List.of(change(path, before, before + "\n@PostMapping(\"/b\")\npublic String b() { return \"y\"; }"))))
                .contains(ImpactReason.API_CHANGE);
        assertThat(classifier.classify(List.of(change(path, before, before.replace("\"x\"", "\"z\"")))))
                .doesNotContain(ImpactReason.API_CHANGE);
        assertThat(classifier.classify(List.of(change("src/test/java/UrlControllerTest.java", null, "@GetMapping(\"/t\")"))))
                .as("tests are not API").doesNotContain(ImpactReason.API_CHANGE);
    }

    @Test
    void securityRelevantFilesAndDeletionsAreFlagged() {
        assertThat(classifier.classify(List.of(change("src/main/java/com/agentic/shortener/service/UrlValidator.java", "a", "b"))))
                .contains(ImpactReason.SECURITY_SENSITIVE_FILE);
        assertThat(classifier.classify(List.of(change("src/main/java/com/agentic/shortener/web/CreateRateLimitFilter.java", "a", "b"))))
                .contains(ImpactReason.SECURITY_SENSITIVE_FILE);
        assertThat(classifier.classify(List.of(change("src/main/java/com/agentic/shortener/X.java", "a", null))))
                .contains(ImpactReason.FILE_DELETION);
    }

    @Test
    void aTestAboutASecurityFeatureIsNotItselfSecurityRelevant() {
        assertThat(classifier.classify(List.of(change("src/test/java/com/agentic/shortener/BulkRateLimitFilterTest.java", null, "class T {}"))))
                .isEmpty();
        assertThat(classifier.classify(new DesignDoc("s", List.of(), List.of(
                new IntendedFile("src/test/java/com/agentic/shortener/UrlValidatorAbuseTest.java", FileAction.CREATE, "p")),
                List.of(), List.of()))).isEmpty();
    }

    @Test
    void aLargeDiffIsFlagged() {
        String big = String.join("\n", java.util.Collections.nCopies(80, "int x;"));
        assertThat(classifier.classify(List.of(change("src/main/java/com/agentic/shortener/Big.java", null, big))))
                .contains(ImpactReason.LARGE_DIFF);
    }

    @Test
    void aDesignIsClassifiedFromTheFilesItIntendsToTouch() {
        DesignDoc design = new DesignDoc("s", List.of(), List.of(
                new IntendedFile("src/main/resources/db/migration/V3__x.sql", FileAction.CREATE, "p"),
                new IntendedFile("pom.xml", FileAction.MODIFY, "p"),
                new IntendedFile("src/main/java/com/agentic/shortener/web/QrController.java", FileAction.CREATE, "p"),
                new IntendedFile("src/main/java/com/agentic/shortener/service/Old.java", FileAction.DELETE, "p")),
                List.of(), List.of());
        Set<ImpactReason> reasons = classifier.classify(design);
        assertThat(reasons).contains(ImpactReason.SCHEMA_CHANGE, ImpactReason.DEPENDENCY_CHANGE, ImpactReason.API_CHANGE,
                ImpactReason.FILE_DELETION);
    }

    @Test
    void aPlainDesignNeedsNoApproval() {
        DesignDoc design = new DesignDoc("s", List.of(), List.of(
                new IntendedFile("src/main/java/com/agentic/shortener/service/QrCodeService.java", FileAction.CREATE, "p")),
                List.of(), List.of());
        assertThat(classifier.classify(design)).isEmpty();
    }

    @Test
    void aDeclaredNewDependencyAloneIsEnough() {
        DesignDoc design = new DesignDoc("s", List.of(), List.of(
                new IntendedFile("src/main/java/com/agentic/shortener/service/A.java", FileAction.CREATE, "p")),
                List.of("com.google.zxing:core"), List.of());
        assertThat(classifier.classify(design)).containsExactly(ImpactReason.DEPENDENCY_CHANGE);
    }
}
