# Final engineering summary

**What was built.** One Spring Boot application on PostgreSQL with two functionalities: a URL shortener, and a governed agentic
SDLC orchestrator that turns a requirement into a reviewable git branch of code, tests and documentation by running it through an
explicit dependency graph of stages. Agents propose. Deterministic gates, policies and humans decide. The orchestrator is the point
of the exercise; the shortener is the real codebase it works on.

**Status.** It runs end to end. The three scenarios (greenfield, brownfield, ambiguous) complete through the packaged application
using the real Maven build and test run inside each run's workspace; the full test suite passes. The main thing it does **not** prove
is the quality of a real model's output: the scenarios replay hand-authored responses (details under *Limitations*).

## 1. Plan and rationale

- **Interpretation.** The assignment describes two systems that depend on each other: a URL shortener "from scratch", and an agentic
  engine that drives the SDLC over it with governance. Most of the evaluation weight is on the second ("critical differentiator"), so
  the shortener is kept honest but small and the orchestrator gets the depth.
- **Simplicity for 24 hours.** One monolithic application with two packages, not two services (an architecture test keeps them
  independent); no UI (Swagger and scripts); no extra infrastructure (PostgreSQL only); a small in-process engine instead of
  Temporal/Camunda, because the orchestration logic is what is being demonstrated.
- **Controlled autonomy by construction.** Everything that matters for safety is plain, tested code the model cannot talk its way
  around: the high-impact classifier, the policy rules, the sandbox, the exit gates, the build, and the release verdict. The model
  only proposes.
- **Reproducibility.** Model access sits behind a port with a replay adapter, so demos and end-to-end tests are deterministic and
  need no API key; a live adapter and a recording mode exist for real use.
- **Order of work.** Shortener core and analytics; engine core (state machines, scheduler, persistence); gates, policies,
  approvals; sandboxed tools and agents; re-planning and rollback; scenarios; then verification. Each layer got tests as it landed,
  and the real end-to-end runs found defects the unit tests could not (listed in [TESTING.md](TESTING.md)).

## 2. The assignment, and where each part is

| Requirement | Where |
| --- | --- |
| 4.1 Requirement understanding, ambiguity | `RequirementsAgent` produces a typed spec with ambiguities, proposed assumptions and an ambiguity score; the `ambiguity` gate pauses the run for a human. Ambiguous scenario. |
| 4.2 Task decomposition with dependencies | `PlannerAgent` emits a task graph (validated acyclic); the engine turns it into `impl:*` stages and joins them into validation. All scenarios. |
| 4.3 Codebase reasoning (brownfield) | `CodeIndex` (classes, endpoints, tables, dependencies) plus `ImpactAnalysisAgent`; finds the expiry defect and the rate-limiter bypass risk. Brownfield scenario. |
| 4.4 Workflow orchestration | Explicit DAG in `workflows/sdlc.yaml` with entry and exit gates, parallel stages and joins, a conditional stage, dynamic expansion; cross-stage context and decision lineage; approvals; bounded retries, fallback, rollback and safe-stop; policy guardrails; hash-chained audit; reliability metrics (success rate, retry and rollback frequency, MTTR, latency); re-planning when upstream output changes. See [ARCHITECTURE.md](ARCHITECTURE.md). |
| 4.5 Production-quality output | Code, Flyway migrations, OpenAPI (`/v3/api-docs`), unit and integration tests, docs, all committed to the run's branch; the shortener itself is tested to 96% line coverage. |
| 4.6 Validation and risk control | Exit gates, the real build, coverage and "every changed class has a test" rules; section 5 below. |
| 4.7 Controlled autonomy | Autonomy level per stage; mandatory human approval for schema, API, security, dependency, deletion, large-diff and release; mandatory comments; the model never merges or pushes. |
| 4.8 Final engineering summary | This document; and each run commits its own `ENGINEERING_SUMMARY.md` (with the decision log) to its branch. |

| Deliverable | Where |
| --- | --- |
| Working prototype, runnable end to end | the application; `./scripts/run-scenario.*` |
| Architecture overview | [ARCHITECTURE.md](ARCHITECTURE.md) (components, orchestration model, control flow, key decisions) |
| Three scenarios, each with decomposition, orchestration, validation | [SCENARIOS.md](SCENARIOS.md), `scenarios/` |
| Setup instructions | [README.md](../README.md) |
| Testing approach, limitations, trade-offs | [TESTING.md](TESTING.md) |

## 3. Artifacts

| What | Path |
| --- | --- |
| Shortener | `src/main/java/com/agentic/shortener` (API, domain, service, web) |
| Orchestrator | `src/main/java/com/agentic/orchestrator` (`engine`, `gate`, `policy`, `approval`, `agent`, `llm`, `tool`, `audit`, `api`, `scenario`) |
| Workflow and policy definitions | `src/main/resources/workflows/sdlc.yaml`, `src/main/resources/policies/default-policy.yaml` |
| Schema | `src/main/resources/db/migration/V1__shortener.sql`, `V2__orchestrator.sql` |
| API definition | OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` |
| Scenarios | `scenarios/<name>/scenario.yaml` and `responses/` |
| Tests | `src/test/java` |
| Scripts | `scripts/run-scenario.ps1`, `scripts/run-scenario.sh`, `scripts/db-init.sql` |

## 4. Validation

- **Test suite.** See [TESTING.md](TESTING.md): unit, architecture, engine control-path and HTTP tests against PostgreSQL, plus
  three end-to-end scenario tests. `mvn verify -Pscenarios` ran **227 tests with 0 failures** (1 skipped, a symlink test that needs an OS
  privilege). Measured line coverage: **95.7% for the shortener** and **90.3% for the orchestrator**.
- **Scenarios end to end** with the real build: all complete. Measured results and the resulting branch are in
  [SCENARIOS.md](SCENARIOS.md#measured-results).
- **Defects found by running it**, all fixed and covered: a real product bug (create/idempotent-retry timestamps differing by
  precision), a classifier false positive on test files, an indexer miss, an architecture-boundary violation, rollback discarding
  unrelated work, a validation loop that would have blamed the tests, a mis-detected HTTP timeout, a stage that hit the rejection cap
  taking an illegal state transition, a run left in `CREATED` and an unauthenticated cost-bearing endpoint.

## 5. Risks, trade-offs and failure scenarios

| Risk or failure scenario | Guardrail | Residual risk |
| --- | --- | --- |
| The model returns invalid or off-spec output | Typed `schema` gate; the failure is fed back; bounded retries; fallback agent; then the run fails | A real model may fail more often than the scripted one |
| The model tries to change what it must not (applied migrations, the engine itself, secrets, dangerous APIs, files outside the design) | Policy gates scan the patch **before anything is written**; protected paths; design-scope gate | Policies are rules: a novel unsafe pattern the rules do not describe gets through to human review |
| Prompt injection from repository content | Content is quoted as data; every tool call and patch is re-checked by code regardless of what the model "decided"; no generic exec exists | A clever injection could still steer *proposals*, which humans and gates then judge |
| Agent escapes the workspace | Path canonicalisation (no `..`, absolute, symlink or `.git`), a git subcommand allowlist (no push, fetch, merge, checkout), ArchUnit rule that only the tool layer starts processes | Sandbox is in-process, not an OS container |
| A change is wrong but compiles | Real build, whole shortener suite, coverage bar, "every changed class has a test", human approval of high-impact changes | Tests can be wrong too; coverage is a floor, not proof |
| A run overspends or loops | Token budget with safe-stop and resume; bounded attempts, fix loops, rejections and clarification rounds; safe-stop after 5 consecutive model failures | Token counts in replay are estimates; real cost needs real measurement |
| The process crashes mid-run | State persisted on every transition; recovery re-queues interrupted stages | Work in a stage that was mid-flight is redone, not salvaged |
| The audit trail is altered | Hash chain with a verification endpoint | An attacker who can rewrite the whole table and recompute every hash is not stopped; anchoring the head hash externally would |
| Analytics hurts redirects | Clicks recorded asynchronously on a bounded queue, dropped and counted when full | Analytics can under-count under overload |

**Main trade-offs.** Replay over live (reproducible, but unproven against a real model). A custom engine over a framework
(transparent, but less battle-tested, hence the exhaustive state-machine and control-path tests). Rebuilding the workspace on
rollback over resetting to a checkpoint (minimal recompute, but the branch history is rewritten before review). One shared approval
key over real identity (simple, but no per-person authority).

## 6. Assumptions

- A single deployment with one instance, one database, and a trusted network; human reviewers are trusted and use the approval key.
- The orchestrator's target is this repository's shortener code; "release" means a branch for human review, never a merge or push.
- Java 25 or newer, Maven, PostgreSQL 16+, git on the machine running the orchestrator.
- The runs are started from a committed state (they work on a git worktree of `HEAD`), or from a copy via `ORCHESTRATOR_SOURCE_ROOT`.

## 7. Limitations, and what I would do next

**Limitations.**
- **No live-model evidence.** The scenario responses are hand-authored, so the output quality of a real model, the prompts' effectiveness
  and real token costs are unmeasured. The live adapter is tested against a fake of the Messages API only.
- Single instance; one shared key for human decisions, no users or roles; no UI.
- The code index is regex-based and specific to this codebase; the build runs only the shortener's tests.
- Rate limiting and the bulk endpoint's cost model are simple (a bulk request counts as one create).

**Next steps, in order.** (1) Run each scenario's requirement against the live model in `record` mode, review what it actually
produces, and tune the prompts; commit those recordings as the scenarios. (2) Real identity and roles for approvals, and a PR-opening
step instead of a bare branch. (3) Per-run isolation in a container for the build and tools. (4) Replace the regex code index with a
parser and extend the build to other modules. (5) Multi-instance scheduling (the state is already in PostgreSQL) and tracing.

## 8. How to review this in fifteen minutes

1. Read the [README](../README.md) setup (five commands) and run `./scripts/run-scenario.sh brownfield autopilot`.
2. Open `GET /api/v1/runs/<id>` (the graph and every stage's state), `/decisions` (who decided what), `/api/v1/audit/verify`, and
   `GET /api/v1/metrics/reliability`.
3. Look at the branch: `git log agent/<runId>` and `git diff <baseline>..agent/<runId>`.
4. Read [ARCHITECTURE.md](ARCHITECTURE.md) sections 2 and 6, then `ControlPathsTest` for how each control is proven.
