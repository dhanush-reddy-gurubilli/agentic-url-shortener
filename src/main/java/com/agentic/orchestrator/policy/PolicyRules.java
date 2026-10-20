package com.agentic.orchestrator.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The built-in policy rules, grouped by category (security, compliance, change control). */
public final class PolicyRules {

    private PolicyRules() {
    }

    private static boolean isMainJava(String path) {
        return path.startsWith("src/main/java/") && path.endsWith(".java");
    }

    // ---- change control ------------------------------------------------------------------------------------

    /** The orchestrator, its policies, workflows and scenarios are off limits to agents, so the engine can't edit itself. */
    public record ProtectedPaths(List<String> prefixes) implements PolicyRule {
        public String id() {
            return "protected-paths";
        }

        public String category() {
            return "change-control";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                for (String prefix : prefixes) {
                    if (c.path().startsWith(prefix) || c.path().equals(prefix)) {
                        out.add(violation(c.path(), "path is protected (" + prefix + "); agents may not change it"));
                        break;
                    }
                }
            }
            return out;
        }
    }

    /** Flyway migrations are append-only: an applied migration must never be edited or removed. */
    public record MigrationsAppendOnly() implements PolicyRule {
        private static final Pattern MIGRATION = Pattern.compile("^src/main/resources/db/migration/V\\d+__.*\\.sql$");

        public String id() {
            return "migrations-append-only";
        }

        public String category() {
            return "change-control";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                if (MIGRATION.matcher(c.path()).matches() && c.oldContent() != null) {
                    out.add(violation(c.path(), "existing migration must not be modified or deleted; add a new versioned migration"));
                }
            }
            return out;
        }
    }

    public record Limits(int maxFiles, int maxChangedLines) implements PolicyRule {
        public String id() {
            return "change-size-limits";
        }

        public String category() {
            return "change-control";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            if (changes.size() > maxFiles) {
                out.add(violation(null, changes.size() + " files changed; the limit per change set is " + maxFiles));
            }
            int lines = changes.stream().mapToInt(c -> c.addedLines().size() + c.removedLines().size()).sum();
            if (lines > maxChangedLines) {
                out.add(violation(null, lines + " changed lines; the limit per change set is " + maxChangedLines));
            }
            return out;
        }
    }

    // ---- security ------------------------------------------------------------------------------------------

    public record Secrets() implements PolicyRule {
        private static final List<Pattern> PATTERNS = List.of(
                Pattern.compile("AKIA[0-9A-Z]{16}"),
                Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                Pattern.compile("(?i)(api[_-]?key|secret|passw(or)?d|token)\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']"),
                Pattern.compile("sk-[A-Za-z0-9_-]{20,}"));
        private static final Pattern QUOTED_TOKEN = Pattern.compile("[\"']([A-Za-z0-9+/=_-]{24,})[\"']");
        private static final Pattern SECRETISH = Pattern.compile("(?i)key|secret|token|passw|credential|auth");

        public String id() {
            return "no-secrets";
        }

        public String category() {
            return "security";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                for (String line : c.addedLines()) {
                    boolean hit = PATTERNS.stream().anyMatch(p -> p.matcher(line).find());
                    if (!hit && SECRETISH.matcher(line).find()) {
                        Matcher m = QUOTED_TOKEN.matcher(line);
                        while (m.find() && !hit) {
                            hit = entropy(m.group(1)) > 4.3;
                        }
                    }
                    if (hit) {
                        out.add(violation(c.path(), "possible secret committed in: " + abbreviate(line.strip())));
                        break;
                    }
                }
            }
            return out;
        }

        static double entropy(String s) {
            int[] counts = new int[256];
            for (char ch : s.toCharArray()) {
                counts[ch & 0xFF]++;
            }
            double h = 0;
            for (int n : counts) {
                if (n > 0) {
                    double p = (double) n / s.length();
                    h -= p * (Math.log(p) / Math.log(2));
                }
            }
            return h;
        }
    }

    /** Generated code may not spawn processes or kill the JVM. */
    public record DangerousApis() implements PolicyRule {
        private static final List<String> FORBIDDEN = List.of("Runtime.getRuntime().exec", "ProcessBuilder", "System.exit(");

        public String id() {
            return "no-dangerous-apis";
        }

        public String category() {
            return "security";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                if (!c.path().endsWith(".java")) {
                    continue;
                }
                for (String line : c.addedLines()) {
                    for (String token : FORBIDDEN) {
                        if (line.contains(token)) {
                            out.add(violation(c.path(), "forbidden API '" + token + "'"));
                        }
                    }
                }
            }
            return out;
        }
    }

    /** SQL must use bind parameters, never string concatenation. */
    public record SqlConcatenation() implements PolicyRule {
        private static final Pattern CONCAT = Pattern.compile(
                "(?i)\"\\s*(select|insert|update|delete)\\b[^\"]*\"\\s*\\+");

        public String id() {
            return "no-sql-concatenation";
        }

        public String category() {
            return "security";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                if (!isMainJava(c.path())) {
                    continue;
                }
                for (String line : c.addedLines()) {
                    if (CONCAT.matcher(line).find()) {
                        out.add(violation(c.path(), "SQL built by string concatenation: " + abbreviate(line.strip())));
                    }
                }
            }
            return out;
        }
    }

    /** New dependencies must come from an allowlisted group id. */
    public record DependencyAllowlist(List<String> allowedGroups) implements PolicyRule {
        private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");

        public String id() {
            return "dependency-allowlist";
        }

        public String category() {
            return "security";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                if (!c.path().equals("pom.xml") || c.newContent() == null) {
                    continue;
                }
                Set<String> before = groups(c.oldContent());
                for (String group : groups(c.newContent())) {
                    if (!before.contains(group) && !allowedGroups.contains(group)) {
                        out.add(violation(c.path(), "new dependency group '" + group + "' is not on the allowlist"));
                    }
                }
            }
            return out;
        }

        private static Set<String> groups(String pom) {
            Set<String> groups = new HashSet<>();
            if (pom != null) {
                Matcher m = GROUP.matcher(pom);
                while (m.find()) {
                    groups.add(m.group(1));
                }
            }
            return groups;
        }
    }

    // ---- compliance ----------------------------------------------------------------------------------------

    /** Client IPs and other direct identifiers must never be stored raw (hashes only). */
    public record NoRawPii() implements PolicyRule {
        private static final Pattern RAW_IP = Pattern.compile(
                "(?i)\\b(client_?ip|ip_?address|remote_?addr(ess)?|user_?ip)\\b");

        public String id() {
            return "no-raw-pii";
        }

        public String category() {
            return "compliance";
        }

        public List<Violation> check(List<ProposedChange> changes) {
            List<Violation> out = new ArrayList<>();
            for (ProposedChange c : changes) {
                boolean relevant = isMainJava(c.path()) || c.path().endsWith(".sql");
                if (!relevant) {
                    continue;
                }
                for (String line : c.addedLines()) {
                    String normalized = line.toLowerCase(Locale.ROOT);
                    if (RAW_IP.matcher(line).find() && !normalized.contains("hash") && !normalized.contains("//")) {
                        out.add(violation(c.path(), "possible raw IP / identifier storage (store a salted hash only): "
                                + abbreviate(line.strip())));
                    }
                }
            }
            return out;
        }
    }

    static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
