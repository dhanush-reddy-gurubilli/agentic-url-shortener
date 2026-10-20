# Testing, limits and trade-offs

## Approach

Three layers, each answering a different question. Everything runs against a real PostgreSQL database (`agentic_test`) and, for
the engine, a real git workspace, because the engine's behaviour is mostly about persisted state and files.

| Layer | Question it answers | How |
| --- | --- | --- |
| **Unit and architecture** | Are the building blocks right in isolation? | Pure JUnit: transition tables checked pair by pair, workflow graph validation (cycles, unknown stages), every policy rule with a passing and a failing case, the high-impact classifier, the sandbox (traversal, absolute paths, `.git`, symlinks, command allowlist), exit gates, MTTR and percentile maths, audit-chain tampering, the code index. ArchUnit enforces the package boundary and "only the tool layer may start a process". |
| **Engine control paths** | Does the orchestrator behave correctly when things go wrong? | The real engine, gates, policies, store and git workspaces, with a *scripted model* and a *fake build* (`ScriptedLlm`, `FakeBuildRunner`). One test per control path; see below. |
| **Scenarios end to end** | Does the whole thing produce a working change? | The three scenarios with the **real Maven build and test run** inside each run's workspace (against a throw-away database schema), the recorded model responses, and the scripted human. Run with `mvn verify -Pscenarios`. |

Plus the shortener's own tests: unit (code generation, validation, IP hashing, the click recorder under a full queue), service
tests on PostgreSQL (collision retry, idempotency, concurrent creates with exactly one alias winner), HTTP tests (status codes,
problem+json, rate limiting, analytics, no raw IPs stored) and a documented known defect (`ExpiryDefectTest`, fixed by the brownfield scenario).

## Results

`mvn verify -Pscenarios` on a clean checkout: **227 tests, 0 failures, 0 errors, 1 skipped** (the symlink-escape test, which needs
an OS privilege this machine did not grant), in about 3.5 minutes, of which the three real-build scenarios are 131 seconds.

| Coverage (JaCoCo, scenarios included) | Lines | Branches |
| --- | --- | --- |
| `com.agentic.shortener` | 95.7% (246 of 257) | 93.3% |
| `com.agentic.orchestrator` | 90.3% (2,521 of 2,793) | 75.8% |

Weakest orchestrator packages: `api` 68% (mostly the scenario endpoints and request plumbing; the behaviour behind them is tested),
`tool` 88%, `agent` 87%. Coverage is a floor on what was exercised, not evidence of what was checked; the control-path tests assert
outcomes (statuses, branch contents, audit events, prompts), not just that lines ran.

### Control paths covered by `ControlPathsTest`

Invalid model output retried with the gate feedback in the next prompt; retries exhausted then fallback agent; a stage that keeps
failing fails the run; an edited applied migration blocked; the orchestrator's own code protected; a committed secret blocked; a patch
outside the approved design rejected by the scope gate; a rejected design redone with the reviewer's comment; a schema change needing
two checkpoints with nothing applied before approval; approval comments mandatory and decisions not repeatable; an ambiguous requirement
pausing for clarification and re-planning; the token budget safe-stopping the run and resume needing more budget; a human safe-stop
honoured between stages; a failing build re-opening the right code stages with the failure as feedback; validation that never passes
failing after bounded attempts; manual rollback re-running everything downstream; a changed plan re-opening only the affected tasks
(unchanged tasks are not redone, removed tasks retired, new ones added, the rolled-back commit gone from the branch); an edited
requirement re-deriving the spec while identical output re-opens nothing; a design rejected three times failing the run rather than
looping; and crash recovery. `ConsecutiveFailureStopTest` covers the safe-stop after repeated model failures and resuming once the model is back.

## How the model is handled in tests

- Engine tests use a **scripted model** and a **fake build**, so they are fast and deterministic and can fail things on purpose.
- Scenario tests use **replay**: model responses are scripted files in `scenarios/<name>/responses`. They are authored by hand,
  **not recorded from a live model**: they prove the orchestration, the gates, the policies and the validation, and they contain
  real, compilable code so the real build and the real test suite genuinely pass or fail.
- The **live** adapter is tested against a local fake of the Messages API (request contents, token accounting, refusal, truncation,
  HTTP errors, timeout). It has **not** been run against the real service, so prompt quality with a real model is unmeasured.

## Defects the tests found during development

The layers above found real problems. Each was fixed and now has a test (all but the last two were first caught by a test run; those
two were spotted in review):

- The real build caught a **product defect**: the create response had nanosecond timestamps while PostgreSQL stored microseconds, so an
  idempotent retry returned a different `createdAt` (a test passed by luck of the clock until the full run). Now truncated at creation.
- The classifier asked a human to approve a **test file** because its name contained "ratelimit". Tests cannot change runtime
  behaviour, so they are excluded from the security-relevant-file rule.
- The code index missed bare `@PostMapping` annotations, which would have hidden real endpoints from impact analysis.
- ArchUnit found a **gate depending on the engine package**; the cycle check moved to a neutral `graph.Dag`.
- Rolling back to a checkpoint discarded unrelated stages that happened to commit later. Rollback now rebuilds the workspace from
  the stored patches of only the stages that stay.
- A validation failure naming only test files would have blamed the tests forever. It now also re-opens the implementation stages, and
  the build report carries the failing assertion and the production stack frame.
- A stage that hit the rejection cap tried an illegal `AWAITING_APPROVAL -> FAILED` transition (found by the test that rejects a design
  three times); a stage waiting on a human may now fail.
- A call-level timeout from the HTTP client was not recognised as a timeout by the live adapter.
- (Review) A run whose workspace could not be created was left in `CREATED`; it is now recorded as stopped with the reason.
- (Review) Starting a run in `live` mode spends money but was unauthenticated; it now needs the approval key outside replay mode.

## Limitations and trade-offs

**What this does not prove**

- Quality of real model output. Replay shows the engine handles good output, bad output, refusals, timeouts and surprises correctly;
  it does not show a real model would produce the changes the scenarios script.
- Behaviour on repositories other than this one. The orchestrator targets the shortener's source; the code index, the "only the
  shortener tests" build and the protected paths are specific to it.

**Deliberate simplifications**

- Single instance. State is in PostgreSQL and the audit chain uses an advisory lock, but the scheduler's per-run locks are in memory.
- Authentication is one shared key for human decisions; there are no user accounts. Roles (who may approve what) are not modelled.
- Click recording is lossy under overload by design (counted in `shortener.clicks.dropped`); redirect latency wins over analytics completeness.
- The create rate limit is an in-memory per-IP counter, and a bulk request counts as one create.
- Rollback rewrites the run's branch (it is rebuilt from the baseline), which is fine before review but means the branch history is not append-only.
- The build runs only the shortener's tests inside a workspace (the orchestrator is a protected path agents cannot change), and
  coverage is the shortener package's line coverage.
- No UI: Swagger UI, the REST API and the scripts are the interface.

**Known risks**

- Scripted human decisions in the demo (`autopilot`) are clearly labelled in the audit trail, but a real deployment must not enable
  them; they exist only behind `POST /api/v1/scenarios/{name}/run?autopilot=true`.
- `live` mode starts runs that spend money; starting one needs the approval key, and the token budget safe-stops a run that overspends.
- The default database password, IP-hash salt and approval key are development defaults and must be changed.
