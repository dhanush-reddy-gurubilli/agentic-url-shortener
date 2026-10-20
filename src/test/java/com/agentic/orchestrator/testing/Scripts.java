package com.agentic.orchestrator.testing;

import com.agentic.orchestrator.artifact.Artifacts.Ambiguity;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.ChangeType;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.DesignDecision;
import com.agentic.orchestrator.artifact.Artifacts.FileAction;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.artifact.Artifacts.ImpactReport;
import com.agentic.orchestrator.artifact.Artifacts.IntendedFile;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Artifacts.Task;
import com.agentic.orchestrator.artifact.Artifacts.TaskGraph;
import com.agentic.orchestrator.artifact.Json;

import java.util.List;

/** Builders for the JSON the scripted model returns in engine tests. */
public final class Scripts {

    public static final String SERVICE = "src/main/java/com/agentic/shortener/service/QrCodeService.java";
    public static final String HELPER = "src/main/java/com/agentic/shortener/service/QrSizes.java";
    public static final String TEST = "src/test/java/com/agentic/shortener/service/QrCodeServiceTest.java";

    private final Json json;

    public Scripts(Json json) {
        this.json = json;
    }

    public String spec(ChangeType type, double ambiguity, List<Ambiguity> ambiguities) {
        return json.write(new RequirementSpec("QR codes", type, List.of("Offer QR codes"),
                List.of("Return a QR code for a short URL"), List.of("Respond within 200 ms"),
                List.of("GET returns a PNG for a known code"), ambiguities, List.of(), ambiguity));
    }

    public String impact() {
        return json.write(new ImpactReport("Touches ShortenerService.resolve", List.of("ShortenerService"),
                List.of("GET /api/v1/urls/{code}"), List.of("urls"), List.of("UrlController -> ShortenerService"),
                List.of("Cached expiry"), "ShortenerService.resolve skips the expiry check"));
    }

    public String design(IntendedFile... files) {
        return json.write(new DesignDoc("Add QR support", List.of(new DesignDecision("Own service", "Keep it testable")),
                List.of(files), List.of(), List.of()));
    }

    public static IntendedFile create(String path) {
        return new IntendedFile(path, FileAction.CREATE, "new file");
    }

    public static IntendedFile modify(String path) {
        return new IntendedFile(path, FileAction.MODIFY, "changed file");
    }

    public String tasks(Task... tasks) {
        return json.write(new TaskGraph(List.of(tasks)));
    }

    public static Task task(String id, List<String> files, List<String> dependsOn) {
        return new Task(id, "Task " + id, files, dependsOn, List.of("works"));
    }

    public String patch(String summary, FileChange... changes) {
        return json.write(new Patch(summary, List.of(changes)));
    }

    public static FileChange write(String path, String content) {
        return new FileChange(path, ChangeOp.WRITE, content, null, null);
    }

    public static FileChange replace(String path, String find, String replacement) {
        return new FileChange(path, ChangeOp.REPLACE, null, find, replacement);
    }

    public static FileChange delete(String path) {
        return new FileChange(path, ChangeOp.DELETE, null, null, null);
    }

    public String narrative() {
        return "{\"narrative\": \"## What was built\\nQR support.\", \"risks\": [\"Large images are slow\"]}";
    }

    public static String javaClass(String simpleName) {
        return "package com.agentic.shortener.service;\n\npublic class " + simpleName + " {\n}\n";
    }

    /** A test file that names every given class, so the 'tests for changed classes' rule is satisfied. */
    public static String testFor(String... classNames) {
        return "package com.agentic.shortener.service;\n\nclass QrCodeServiceTest {\n    // exercises " + String.join(", ", classNames) + "\n}\n";
    }

    public static String changelog(String title) {
        return "# Changelog\n\n## " + title + "\n\n- done\n";
    }

    /** Scripts the stages of a plain greenfield run: two parallel implementation tasks, tests, docs, release notes. */
    public void happyPath(ScriptedLlm llm) {
        llm.on("requirements", spec(ChangeType.GREENFIELD, 0.1, List.of()));
        llm.on("design", design(create(SERVICE), create(HELPER)));
        llm.on("planning", tasks(task("T1", List.of(SERVICE), List.of()), task("T2", List.of(HELPER), List.of())));
        llm.on("impl:T1", patch("service", write(SERVICE, javaClass("QrCodeService"))));
        llm.on("impl:T2", patch("helper", write(HELPER, javaClass("QrSizes"))));
        llm.on("test_generation", patch("tests", write(TEST, testFor("QrCodeService", "QrSizes"))));
        llm.on("documentation", patch("docs", write("docs/CHANGELOG.md", changelog("QR codes"))));
        llm.on("release_readiness", narrative());
    }
}
