package com.agentic.orchestrator.testing;

import com.agentic.orchestrator.artifact.Artifacts.TestReport;
import com.agentic.orchestrator.tool.BuildRunner;
import com.agentic.orchestrator.tool.Workspace;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Test double for Maven: the build outcome is a function of the workspace, so tests can fail it on purpose. */
public class FakeBuildRunner implements BuildRunner {

    public static final TestReport PASSING = new TestReport(true, 12, 0, 0.91, 1000, List.of(), "BUILD SUCCESS", List.of());

    private volatile Function<Workspace, TestReport> behaviour = ws -> PASSING;
    private final AtomicInteger calls = new AtomicInteger();

    public void reset() {
        behaviour = ws -> PASSING;
        calls.set(0);
    }

    public void behave(Function<Workspace, TestReport> behaviour) {
        this.behaviour = behaviour;
    }

    public int calls() {
        return calls.get();
    }

    public static TestReport failing(String failure) {
        return new TestReport(true, 12, 1, 0.91, 1000, List.of(failure), "Tests run: 12, Failures: 1\n" + failure, List.of());
    }

    @Override
    public TestReport verify(Workspace workspace) {
        calls.incrementAndGet();
        return behaviour.apply(workspace);
    }
}
