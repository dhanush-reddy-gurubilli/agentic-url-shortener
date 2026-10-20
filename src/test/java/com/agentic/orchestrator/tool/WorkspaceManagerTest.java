package com.agentic.orchestrator.tool;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The two ways a run's workspace is created: a git worktree of a real repository, or a copy of a plain directory. */
class WorkspaceManagerTest {

    @TempDir
    Path temp;
    Path source;
    Path workspaceRoot;

    @BeforeEach
    void setUp() throws Exception {
        source = Files.createDirectories(temp.resolve("source"));
        workspaceRoot = temp.resolve("workspaces");
        write(source, "src/main/java/A.java", "class A {}\n");
        write(source, "README.md", "# readme\n");
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private WorkspaceManager manager() {
        var llm = new OrchestratorProperties.Llm("replay", "m", 1000, Duration.ofSeconds(1), Path.of("."));
        return new WorkspaceManager(new OrchestratorProperties(workspaceRoot, source, null, null, llm, 4, 0.5, 5, 1000, null, "k"));
    }

    @Test
    void aPlainDirectoryIsCopiedIntoItsOwnRepositoryOnTheRunsBranch() throws Exception {
        write(source, "target/classes/Junk.class", "junk");
        write(source, "workspaces/old/file.txt", "old");
        UUID id = UUID.randomUUID();

        Workspace ws = manager().create(id);

        assertThat(ws.branch()).isEqualTo("agent/" + id);
        assertThat(ws.fs().read("src/main/java/A.java")).isEqualTo("class A {}\n");
        assertThat(ws.root().resolve("target")).as("build output is not copied").doesNotExist();
        assertThat(ws.root().resolve("workspaces")).as("other runs are not copied").doesNotExist();
        assertThat(ws.git().head()).isEqualTo(ws.baselineSha());
        assertThat(ws.git().run("rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("agent/" + id);
        assertThat(source.resolve(".git")).as("the source is left alone").doesNotExist();
    }

    @Test
    void aGitRepositoryGetsARealWorktreeOnANewBranchAndItsOwnWorkingTreeIsUntouched() throws Exception {
        GitTool sourceGit = new GitTool(source);
        sourceGit.run("init", "-q", "-b", "main");
        String head = sourceGit.commitAll("initial");
        write(source, "uncommitted.txt", "not committed yet");
        UUID id = UUID.randomUUID();

        Workspace ws = manager().create(id);

        assertThat(ws.baselineSha()).as("the worktree starts at the repository's HEAD").isEqualTo(head);
        assertThat(ws.branch()).isEqualTo("agent/" + id);
        assertThat(ws.git().run("rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("agent/" + id);
        assertThat(sourceGit.run("branch", "--list", "agent/" + id)).as("a real branch a human can review and merge").contains("agent/" + id);
        assertThat(ws.fs().exists("README.md")).isTrue();
        assertThat(ws.fs().exists("uncommitted.txt")).as("uncommitted work is not part of a run").isFalse();

        // Work done in the worktree is committed on the branch and does not move the source's own HEAD.
        ws.fs().apply(new FileChange("src/main/java/B.java", ChangeOp.WRITE, "class B {}\n", null, null));
        String checkpoint = ws.git().commitAll("[impl:T1] add B");
        assertThat(checkpoint).isNotEqualTo(head);
        assertThat(sourceGit.head()).isEqualTo(head);
        assertThat(sourceGit.run("log", "--oneline", "agent/" + id)).contains("add B");
        assertThat(source.resolve("src/main/java/B.java")).doesNotExist();
    }

    @Test
    void twoRunsGetIndependentWorkspaces() throws Exception {
        WorkspaceManager manager = manager();
        Workspace first = manager.create(UUID.randomUUID());
        Workspace second = manager.create(UUID.randomUUID());

        first.fs().apply(new FileChange("only-in-first.txt", ChangeOp.WRITE, "x", null, null));

        assertThat(first.root()).isNotEqualTo(second.root());
        assertThat(second.fs().exists("only-in-first.txt")).isFalse();
    }

    @Test
    void anExistingWorkspaceCanBeReopenedAfterARestart() {
        UUID id = UUID.randomUUID();
        Workspace created = manager().create(id);

        Workspace reopened = manager().open(id, created.root().toString(), created.baselineSha());

        assertThat(reopened.branch()).isEqualTo(created.branch());
        assertThat(reopened.git().head()).isEqualTo(created.git().head());
    }
}
