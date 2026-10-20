package com.agentic.orchestrator;

import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.tool.ChangeFailed;
import com.agentic.orchestrator.tool.GitTool;
import com.agentic.orchestrator.tool.SandboxViolation;
import com.agentic.orchestrator.tool.WorkspaceFs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.agentic.orchestrator.artifact.Artifacts.ChangeOp.DELETE;
import static com.agentic.orchestrator.artifact.Artifacts.ChangeOp.REPLACE;
import static com.agentic.orchestrator.artifact.Artifacts.ChangeOp.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The tool layer is the hard boundary of agent autonomy: these tests make sure it fails closed. */
class SandboxTest {

    @TempDir
    Path root;
    WorkspaceFs fs;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {\n  int x = 1;\n}\n");
        fs = new WorkspaceFs(root);
    }

    @Test
    void parentDirectorySegmentsAreRejected() {
        assertThatThrownBy(() -> fs.read("../secret.txt")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.read("src/../../secret.txt")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.read("src\\..\\..\\secret.txt")).isInstanceOf(SandboxViolation.class);
    }

    @Test
    void absolutePathsAreRejected() {
        assertThatThrownBy(() -> fs.read("/etc/passwd")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.read("C:\\Windows\\win.ini")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.read("c:/Windows/win.ini")).isInstanceOf(SandboxViolation.class);
    }

    @Test
    void gitInternalsAreOffLimits() {
        assertThatThrownBy(() -> fs.read(".git/config")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.apply(new FileChange("sub/.git/hooks/pre-commit", WRITE, "x", null, null)))
                .isInstanceOf(SandboxViolation.class);
    }

    @Test
    void emptyPathsAreRejected() {
        assertThatThrownBy(() -> fs.read(" ")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.read(null)).isInstanceOf(SandboxViolation.class);
    }

    @Test
    void aSymlinkPointingOutsideTheWorkspaceIsRejected(@TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symlinks need extra privileges on this machine");
        }
        assertThatThrownBy(() -> fs.read("link/secret.txt")).isInstanceOf(SandboxViolation.class);
        assertThatThrownBy(() -> fs.apply(new FileChange("link/new.txt", WRITE, "x", null, null)))
                .isInstanceOf(SandboxViolation.class);
    }

    @Test
    void writeReplaceAndDeleteWorkInsideTheWorkspace() {
        fs.apply(new FileChange("src/new/B.java", WRITE, "class B {}", null, null));
        assertThat(fs.read("src/new/B.java")).isEqualTo("class B {}");
        fs.apply(new FileChange("src/A.java", REPLACE, null, "int x = 1;", "int x = 2;"));
        assertThat(fs.read("src/A.java")).contains("int x = 2;");
        fs.apply(new FileChange("src/new/B.java", DELETE, null, null, null));
        assertThat(fs.exists("src/new/B.java")).isFalse();
    }

    @Test
    void replaceNeedsAnExactUniqueSnippet() throws Exception {
        Files.writeString(root.resolve("src/D.java"), "x\nx\n");
        assertThatThrownBy(() -> fs.simulate(new FileChange("src/A.java", REPLACE, null, "missing", "y")))
                .isInstanceOf(ChangeFailed.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> fs.simulate(new FileChange("src/D.java", REPLACE, null, "x", "y")))
                .isInstanceOf(ChangeFailed.class).hasMessageContaining("not unique");
        assertThatThrownBy(() -> fs.simulate(new FileChange("src/nope.java", REPLACE, null, "a", "b")))
                .isInstanceOf(ChangeFailed.class);
        assertThatThrownBy(() -> fs.simulate(new FileChange("src/nope.java", DELETE, null, null, null)))
                .isInstanceOf(ChangeFailed.class);
    }

    @Test
    void simulateDoesNotTouchTheDisk() {
        String result = fs.simulate(new FileChange("src/A.java", REPLACE, null, "int x = 1;", "int x = 9;"));
        assertThat(result).contains("int x = 9;");
        assertThat(fs.read("src/A.java")).contains("int x = 1;");
    }

    @Test
    void gitOnlyAllowsTheListedSubcommands() {
        GitTool git = new GitTool(root);
        for (String forbidden : new String[]{"push", "fetch", "remote", "merge", "checkout", "clone", "config", "rebase"}) {
            assertThatThrownBy(() -> git.run(forbidden)).as(forbidden).isInstanceOf(SandboxViolation.class);
        }
    }

    @Test
    void checkpointsCommitAndResetRestoresTheFilesAndDropsLaterCommits() throws Exception {
        GitTool git = new GitTool(root);
        git.run("init", "-q", "-b", "main");
        String first = git.commitAll("first");
        Files.writeString(root.resolve("src/A.java"), "changed");
        Files.writeString(root.resolve("untracked.txt"), "u");
        String second = git.commitAll("second");
        assertThat(second).isNotEqualTo(first);
        Files.writeString(root.resolve("uncommitted.txt"), "x");

        git.resetTo(first);

        assertThat(fs.read("src/A.java")).contains("int x = 1;");
        assertThat(root.resolve("untracked.txt")).doesNotExist();
        assertThat(root.resolve("uncommitted.txt")).doesNotExist();
        assertThat(git.head()).isEqualTo(first);
    }
}
