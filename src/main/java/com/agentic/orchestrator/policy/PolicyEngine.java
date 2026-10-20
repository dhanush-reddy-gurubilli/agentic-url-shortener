package com.agentic.orchestrator.policy;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.policy.PolicyRules.DangerousApis;
import com.agentic.orchestrator.policy.PolicyRules.DependencyAllowlist;
import com.agentic.orchestrator.policy.PolicyRules.Limits;
import com.agentic.orchestrator.policy.PolicyRules.MigrationsAppendOnly;
import com.agentic.orchestrator.policy.PolicyRules.NoRawPii;
import com.agentic.orchestrator.policy.PolicyRules.ProtectedPaths;
import com.agentic.orchestrator.policy.PolicyRules.Secrets;
import com.agentic.orchestrator.policy.PolicyRules.SqlConcatenation;
import com.agentic.orchestrator.tool.WorkspaceFs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Runs every policy rule over a proposed change set. All violations are blocking. */
@Component
public class PolicyEngine {

    private final PolicyConfig config;
    private final List<PolicyRule> rules;

    @Autowired
    public PolicyEngine(OrchestratorProperties properties) {
        this(load(properties));
    }

    public PolicyEngine(PolicyConfig config) {
        this.config = config;
        this.rules = List.of(
                new ProtectedPaths(config.protectedPaths()),
                new MigrationsAppendOnly(),
                new Limits(config.maxFilesPerChangeSet(), config.maxChangedLines()),
                new Secrets(),
                new DangerousApis(),
                new SqlConcatenation(),
                new DependencyAllowlist(config.allowedNewDependencyGroups()),
                new NoRawPii());
    }

    private static PolicyConfig load(OrchestratorProperties properties) {
        try (InputStream in = properties.policy().getInputStream()) {
            return YAMLMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build().readValue(in, PolicyConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load policy " + properties.policy(), e);
        }
    }

    public PolicyConfig config() {
        return config;
    }

    public List<PolicyRule> rules() {
        return rules;
    }

    public List<Violation> evaluate(List<ProposedChange> changes) {
        List<Violation> all = new ArrayList<>();
        for (PolicyRule rule : rules) {
            all.addAll(rule.check(changes));
        }
        return all;
    }

    /**
     * Change control: every changed production class needs a changed test that mentions it.
     * {@code nameStatus} is the output of {@code git diff --name-status baseline HEAD}.
     */
    public List<Violation> missingTests(WorkspaceFs fs, List<String> nameStatus) {
        List<String> changedMain = new ArrayList<>();
        List<String> changedTests = new ArrayList<>();
        for (String line : nameStatus) {
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2 || parts[0].startsWith("D")) {
                continue;
            }
            String path = parts[1].trim().replace('\\', '/');
            if (path.startsWith("src/main/java/com/agentic/shortener/") && path.endsWith(".java")) {
                changedMain.add(path);
            } else if (path.startsWith("src/test/java/") && path.endsWith(".java")) {
                changedTests.add(path);
            }
        }
        List<Violation> out = new ArrayList<>();
        for (String main : changedMain) {
            String simpleName = main.substring(main.lastIndexOf('/') + 1, main.length() - ".java".length());
            Pattern word = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
            boolean covered = changedTests.stream().anyMatch(t -> word.matcher(fs.read(t)).find());
            if (!covered) {
                out.add(new Violation("tests-for-changed-classes", "change-control", main,
                        "changed production class " + simpleName + " has no changed test that exercises it"));
            }
        }
        return out;
    }
}
