package com.agentic.orchestrator.tool;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs {@code mvn verify} for the shortener package inside a workspace, against a throw-away Postgres schema so a
 * workspace's migrations never touch the shared database. Only the shortener's tests are run: the orchestrator is
 * a protected path that agents cannot change, so its own tests are not part of an agent's validation.
 */
@Component
public class MavenBuildRunner implements BuildRunner {

    private static final String SHORTENER_PACKAGE = "com.agentic.shortener";

    private final OrchestratorProperties properties;
    private final JdbcClient jdbc;
    private final Environment environment;

    public MavenBuildRunner(OrchestratorProperties properties, JdbcClient jdbc, Environment environment) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @Override
    public TestReport verify(Workspace workspace) {
        String schema = "ws_" + workspace.runId().toString().replace("-", "").substring(0, 12);
        jdbc.sql("drop schema if exists " + schema + " cascade").update();
        jdbc.sql("create schema " + schema).update();
        long started = System.currentTimeMillis();
        try {
            ProcessRunner.Result result = ProcessRunner.run(mavenCommand(), workspace.root(), databaseEnv(schema),
                    properties.build().timeout());
            return toReport(workspace.root(), result, System.currentTimeMillis() - started);
        } finally {
            jdbc.sql("drop schema if exists " + schema + " cascade").update();
        }
    }

    private List<String> mavenCommand() {
        List<String> argv = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            argv.addAll(List.of("cmd", "/c"));
        }
        argv.addAll(List.of(properties.build().command(), "-B", "-ntp", "verify",
                "-Dtest=com/agentic/shortener/**/*", "-Dsurefire.failIfNoSpecifiedTests=false"));
        return argv;
    }

    private Map<String, String> databaseEnv(String schema) {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String separator = baseUrl.contains("?") ? "&" : "?";
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_URL", baseUrl + separator + "currentSchema=" + schema);
        env.put("DB_USERNAME", environment.getProperty("spring.datasource.username", ""));
        env.put("DB_PASSWORD", environment.getProperty("spring.datasource.password", ""));
        env.put("SPRING_FLYWAY_DEFAULT_SCHEMA", schema);
        return env;
    }

    private TestReport toReport(Path root, ProcessRunner.Result result, long millis) {
        SurefireSummary tests = readSurefire(root.resolve("target/surefire-reports"));
        boolean compiled = !result.output().contains("COMPILATION ERROR")
                && (tests.run > 0 || result.exitCode() == 0);
        double coverage = readCoverage(root.resolve("target/site/jacoco/jacoco.csv"));
        String tail = result.timedOut() ? "Build timed out after " + properties.build().timeout()
                : summarize(result.output());
        return new TestReport(compiled, tests.run, tests.failed, coverage, millis, tests.failures, tail, List.of());
    }

    private record SurefireSummary(int run, int failed, List<String> failures) {
    }

    private static SurefireSummary readSurefire(Path dir) {
        if (!Files.isDirectory(dir)) {
            return new SurefireSummary(0, 0, List.of());
        }
        int run = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                Document doc = factory.newDocumentBuilder().parse(file.toFile());
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element testCase = (Element) cases.item(i);
                    run++;
                    Element problem = firstChild(testCase, "failure");
                    if (problem == null) {
                        problem = firstChild(testCase, "error");
                    }
                    if (problem != null) {
                        failed++;
                        failures.add(describeFailure(testCase, problem));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse surefire reports", e);
        }
        return new SurefireSummary(run, failed, failures);
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    /**
     * "Class.test: message [at ProductionClass.method(File.java:12)]": the assertion message plus the first stack frame
     * in production code, which is what an agent (and the failure-to-stage matching) needs to find the cause.
     */
    static String describeFailure(Element testCase, Element problem) {
        String testName = testCase.getAttribute("classname") + "." + testCase.getAttribute("name");
        String message = problem.getAttribute("message");
        String firstLine = message.isBlank() ? "" : message.lines().findFirst().orElse("");
        String frame = problem.getTextContent().lines().map(String::strip)
                .filter(l -> l.startsWith("at com.agentic.shortener.") && !l.contains("Test."))
                .findFirst().orElse(null);
        return testName + (firstLine.isBlank() ? "" : ": " + firstLine) + (frame == null ? "" : " [" + frame + "]");
    }

    /** Line coverage of the shortener package from JaCoCo's CSV (columns 7 and 8 are LINE_MISSED / LINE_COVERED). */
    static double readCoverage(Path csv) {
        if (!Files.isRegularFile(csv)) {
            return 0;
        }
        long missed = 0;
        long covered = 0;
        try {
            for (String line : Files.readAllLines(csv).stream().skip(1).toList()) {
                String[] cols = line.split(",");
                if (cols.length > 8 && cols[1].startsWith(SHORTENER_PACKAGE)) {
                    missed += Long.parseLong(cols[7]);
                    covered += Long.parseLong(cols[8]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return missed + covered == 0 ? 0 : (double) covered / (missed + covered);
    }

    /** The useful part of a Maven log: error and failure lines, for feeding back to an agent. */
    static String summarize(String output) {
        List<String> keep = output.lines()
                .filter(l -> l.contains("[ERROR]") || l.contains("FAIL") || l.contains("Tests run:"))
                .limit(60).toList();
        String text = keep.isEmpty() ? ProcessRunner.tail(output, 3000) : String.join("\n", keep);
        return ProcessRunner.tail(text, 6000);
    }
}
