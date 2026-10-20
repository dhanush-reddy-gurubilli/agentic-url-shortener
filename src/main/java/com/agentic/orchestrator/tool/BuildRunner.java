package com.agentic.orchestrator.tool;

import com.agentic.orchestrator.artifact.Artifacts.TestReport;

/** Compiles and tests a workspace. Swappable so engine tests don't run Maven. */
public interface BuildRunner {

    TestReport verify(Workspace workspace);
}
