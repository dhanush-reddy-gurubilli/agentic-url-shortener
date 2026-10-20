package com.agentic.orchestrator;

import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.policy.PolicyRule;
import com.agentic.orchestrator.policy.PolicyRules;
import com.agentic.orchestrator.policy.ProposedChange;
import com.agentic.orchestrator.policy.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyRulesTest {

    private static ProposedChange change(String path, String before, String after) {
        return new ProposedChange(new FileChange(path, after == null ? ChangeOp.DELETE : ChangeOp.WRITE, after, null, null),
                before, after);
    }

    private static List<Violation> check(PolicyRule rule, ProposedChange... changes) {
        return rule.check(List.of(changes));
    }

    // ---- change control ------------------------------------------------------------------------------------------

    @Test
    void protectedPathsAreBlocked() {
        var rule = new PolicyRules.ProtectedPaths(List.of("src/main/java/com/agentic/orchestrator/", "scenarios/"));
        assertThat(check(rule, change("src/main/java/com/agentic/orchestrator/engine/X.java", null, "x"))).hasSize(1);
        assertThat(check(rule, change("scenarios/greenfield/scenario.yaml", "a", "b"))).hasSize(1);
        assertThat(check(rule, change("src/main/java/com/agentic/shortener/X.java", null, "x"))).isEmpty();
    }

    @Test
    void anExistingMigrationMustNotBeModifiedOrDeletedButANewOneIsFine() {
        var rule = new PolicyRules.MigrationsAppendOnly();
        String v1 = "src/main/resources/db/migration/V1__init.sql";
        assertThat(check(rule, change(v1, "create table a();", "create table b();"))).hasSize(1);
        assertThat(check(rule, change(v1, "create table a();", null))).hasSize(1);
        assertThat(check(rule, change("src/main/resources/db/migration/V3__more.sql", null, "alter table a add b int;"))).isEmpty();
        assertThat(check(rule, change("src/main/resources/application.sql", "a", "b"))).isEmpty();
    }

    @Test
    void changeSizeLimitsCountFilesAndLines() {
        var rule = new PolicyRules.Limits(2, 10);
        assertThat(check(rule, change("a", null, "1"), change("b", null, "1"), change("c", null, "1"))).hasSize(1);
        String big = String.join("\n", java.util.Collections.nCopies(20, "line"));
        assertThat(check(rule, change("a", null, big))).hasSize(1);
        assertThat(check(rule, change("a", null, "small"))).isEmpty();
    }

    // ---- security ------------------------------------------------------------------------------------------------

    @Test
    void secretsAreDetectedByPatternAndByEntropyNextToASecretishName() {
        var rule = new PolicyRules.Secrets();
        assertThat(check(rule, change("A.java", null, "String k = \"AKIAABCDEFGHIJKLMNOP\";"))).hasSize(1);
        assertThat(check(rule, change("A.java", null, "-----BEGIN RSA PRIVATE KEY-----"))).hasSize(1);
        assertThat(check(rule, change("A.java", null, "String password = \"hunter2hunter2hunter2\";"))).hasSize(1);
        assertThat(check(rule, change("A.java", null, "String token = \"q8Zr3Lw9XkP2mVb7NsT4yHcJ6dFgA1eU\";"))).hasSize(1);
        // A long base62 alphabet is not a secret unless it sits next to a secret-like name.
        assertThat(check(rule, change("A.java", null, "ALPHABET = \"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef\";"))).isEmpty();
        assertThat(check(rule, change("A.java", null, "String greeting = \"hello world\";"))).isEmpty();
    }

    @Test
    void onlyAddedLinesAreScannedSoExistingCodeIsNotBlamed() {
        var rule = new PolicyRules.Secrets();
        String existing = "String k = \"AKIAABCDEFGHIJKLMNOP\";\nint a = 1;";
        assertThat(check(rule, change("A.java", existing, existing + "\nint b = 2;"))).isEmpty();
    }

    @Test
    void dangerousApisAreBlockedInJava() {
        var rule = new PolicyRules.DangerousApis();
        assertThat(check(rule, change("A.java", null, "Runtime.getRuntime().exec(cmd);"))).hasSize(1);
        assertThat(check(rule, change("A.java", null, "new ProcessBuilder(\"ls\").start();"))).hasSize(1);
        assertThat(check(rule, change("A.java", null, "System.exit(1);"))).hasSize(1);
        assertThat(check(rule, change("notes.md", null, "ProcessBuilder is discussed here"))).isEmpty();
        assertThat(check(rule, change("A.java", null, "int x = 1;"))).isEmpty();
    }

    @Test
    void sqlBuiltByConcatenationIsBlocked() {
        var rule = new PolicyRules.SqlConcatenation();
        String path = "src/main/java/com/agentic/shortener/Repo.java";
        assertThat(check(rule, change(path, null, "String q = \"select * from urls where code = '\" + code + \"'\";"))).hasSize(1);
        assertThat(check(rule, change(path, null, "String q = \"select * from urls where code = :code\";"))).isEmpty();
        assertThat(check(rule, change("src/test/java/T.java", null, "String q = \"select 1\" + x;"))).isEmpty();
    }

    @Test
    void newDependenciesMustBeOnTheAllowlist() {
        var rule = new PolicyRules.DependencyAllowlist(List.of("com.google.zxing"));
        String before = "<dependency><groupId>org.springframework.boot</groupId></dependency>";
        String zxing = before + "<dependency><groupId>com.google.zxing</groupId></dependency>";
        String evil = before + "<dependency><groupId>com.evil</groupId></dependency>";
        assertThat(check(rule, change("pom.xml", before, zxing))).isEmpty();
        assertThat(check(rule, change("pom.xml", before, evil))).singleElement()
                .satisfies(v -> assertThat(v.message()).contains("com.evil"));
        assertThat(check(rule, change("pom.xml", before, before))).isEmpty();
    }

    // ---- compliance ----------------------------------------------------------------------------------------------

    @Test
    void rawIpStorageIsBlockedButHashedIpsAreFine() {
        var rule = new PolicyRules.NoRawPii();
        String entity = "src/main/java/com/agentic/shortener/domain/ClickEvent.java";
        assertThat(check(rule, change(entity, null, "private String clientIp;"))).hasSize(1);
        assertThat(check(rule, change("src/main/resources/db/migration/V3__x.sql", null, "ip_address varchar(45)"))).hasSize(1);
        assertThat(check(rule, change(entity, null, "private String ipHash;"))).isEmpty();
        assertThat(check(rule, change(entity, null, "String clientIpHash = hash(ip);"))).isEmpty();
    }

    @Test
    void aLowEntropyStringNextToASecretishWordIsNotMistakenForARandomSecret() {
        assertThat(check(new PolicyRules.Secrets(),
                change("A.java", null, "String authMode = \"Xxxxxxxxxxxxxxxxxxxxxxxxxxxx\";"))).isEmpty();
    }

    @Test
    void aLongLiteralAssignedToATokenVariableIsBlockedEvenIfItLooksRepetitive() {
        assertThat(check(new PolicyRules.Secrets(),
                change("A.java", null, "String token = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaa\";"))).hasSize(1);
    }
}
