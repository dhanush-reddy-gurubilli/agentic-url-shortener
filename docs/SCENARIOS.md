# Scenarios

Three runs of the orchestrator against the shortener's own source: a new feature (greenfield), a change to existing code
(brownfield) and a vague requirement (ambiguous). Each shows how the requirement is understood, how it is decomposed, how the
run is orchestrated, and how the result is validated.

## How a scenario works

Each scenario is a folder under `scenarios/<name>/`:

- `scenario.yaml`: the requirement, a token budget, **faults to inject** (a model timeout, a token surge) and the **scripted
  human's** decisions.
- `responses/<stage>[.<n>].json`: what the model "says" at each stage. `.<n>` means "the n-th time this stage is started in the run";
  a file without a number is used for every other start. These are hand-authored and contain real, compilable code, so the real
  build and the real test suite genuinely pass or fail. They are **not** recorded from a live model (see
  [TESTING.md](TESTING.md#how-the-model-is-handled-in-tests)).

Run one against a live instance (see the README for setup):

```bash
./scripts/run-scenario.sh greenfield autopilot     # a scripted human answers each checkpoint, audited as "autopilot:scripted-human"
./scripts/run-scenario.sh brownfield               # you are the human: it stops at each checkpoint and prints the commands
```

After a run, these answer "what happened and why": `GET /api/v1/runs/{id}` (the graph with every stage's state, attempts and
checkpoint), `/decisions` (the decision log), `/audit` (the hash-chained trail), `/api/v1/audit/verify`, `/api/v1/metrics/reliability`.
The code is on the branch `agent/<runId>`; the engineering summary is `docs/runs/<runId>/ENGINEERING_SUMMARY.md` on it.

---

## 1. Greenfield: QR codes for short URLs

> *Add a QR-code endpoint for short URLs: `GET /api/v1/urls/{code}/qr` returns a PNG (default) or SVG QR code that encodes the short URL, with an optional size in pixels.*

**Requirement understanding.** A clear requirement: ambiguity score 0.1, no questions. The spec records the assumptions it made
(the code encodes the short URL, not the destination; error correction level M) and four testable acceptance criteria.

**Decomposition.** Design (files: `pom.xml`, `QrCodeService`, `QrController`, two tests) then a plan of three tasks:

| Task | Files | Depends on | Runs |
| --- | --- | --- | --- |
| `T1` add the ZXing core dependency | `pom.xml` | none | in parallel |
| `T2` implement `QrCodeService` (PNG and SVG) | `QrCodeService.java` | none | in parallel |
| `T3` expose the endpoint | `QrController.java` | `T2` | after `T2` |

`test_generation` and `documentation` run **at the same time** as `T1`/`T2`; `validation` is the join that waits for all of them.
`impact_analysis` is **skipped** (greenfield).

**Orchestration shown.**

- Parallel fan-out and a join, with the dynamic stages created from the plan.
- A **new dependency** is a high-impact change: the design (`DEPENDENCY_CHANGE`, `API_CHANGE`), `impl:T1` (`DEPENDENCY_CHANGE`) and
  `impl:T3` (`API_CHANGE`) each wait for a human, and nothing is written to the branch before the decision. The dependency policy also
  checks `com.google.zxing` is on the allowlist.
- **Injected fault:** the documentation agent's model calls time out on both attempts. The stage retries once, then switches to the
  template writer (`DOC_TEMPLATE`) and still succeeds.
- Release is always a human decision.

**Validation.** The real Maven build ran in the run's workspace against its own database schema: it compiles, the whole shortener
test suite passes (including the new tests, which decode the generated PNG back to the short URL and check the SVG is well-formed,
the size bounds and the 404/410 behaviour), line coverage is at least 80%, and the "every changed class has a test" rule holds.

---

## 2. Brownfield: expired links still redirect, and bulk create

> *Users report that expired short links still redirect sometimes. Fix it, and add a bulk-create endpoint that accepts up to 100 URLs in one request.*

**Codebase reasoning.** `impact_analysis` runs (it is skipped for greenfield). It builds a deterministic index of the code (classes,
endpoints, tables, dependencies) and reasons over that. It finds the cause: expiry is checked only when a link is loaded into the
redirect cache, so a link that expires while cached keeps redirecting; and it flags a risk nobody asked about: the create rate
limiter matches only `POST /api/v1/urls`, so a bulk endpoint would bypass it.

**Decomposition.**

| Task | Files | Depends on |
| --- | --- | --- |
| `T1` enforce expiry on every redirect; add `createBatch` | `ShortenerService.java` | none |
| `T2` bulk DTOs and `POST /api/v1/urls/bulk` | `ShortenerDtos.java`, `UrlController.java` | `T1` |
| `T3` partial index on `expires_at` (new migration) | `V3__urls_expiry_index.sql` | none |
| `T4` rate-limit the bulk path | `CreateRateLimitFilter.java` | none |

**Orchestration shown.**

- **Change control, blocked by policy.** The first attempt at `T3` edits the already-applied `V1` migration (and is also outside
  the approved design). Policy blocks it before anything is written; the feedback goes to the next attempt, which adds `V3` instead.
- **Validation loop with rollback.** The first version of the `T1` fix forgets that `expiresAt` can be null, so every redirect for a
  link without an expiry breaks: **10 of the existing tests fail** in the real build. Validation fails; the failure names
  `ShortenerService` (the production stack frame) and the test classes, so the engine re-opens `T1`, the test stage and everything
  downstream (`T2`, validation, release), **rebuilds the workspace from the stored patches of the stages that stay** (`T3`, `T4` and the
  docs are neither undone nor redone), and the fix is redone with the failing assertions as feedback.
- **Three kinds of high-impact approval:** a schema change (`T3`, and the design), an API change (`T2`, and the design) and a
  security-relevant file (`T4`, the rate limiter). Re-opened stages ask again.
- A regression test replaces the test that documented the defect (`ExpiryDefectTest`) and proves the fix, including the exact-expiry boundary.

**Validation.** As for greenfield, plus the regression tests, and the proof that `V1` is byte-for-byte unchanged on the branch.

---

## 3. Ambiguous: "make the shortener safer against abuse"

**Requirement understanding.** The first spec scores 0.85 for ambiguity and asks three questions with a proposed assumption for each
(which abuse? reject at creation or detect later? external services allowed?). The run **pauses** (`AWAITING_CLARIFICATION`); nothing
is designed on a guess.

**What happens.**

1. **Clarification and re-plan.** The human accepts the proposed assumptions (URL hygiene only, reject at creation, no external
   services). The requirements stage is redone with the answers (generation 2) and now scores 0.15.
2. **Rejection and re-plan.** The first design is too broad: it also rewrites the rate limiter and adds Guava. It needs approval
   (`DEPENDENCY_CHANGE`, `SECURITY_SENSITIVE_FILE`) and a reviewer **rejects it with a reason**. The comment goes into the next
   attempt's prompt as a constraint; the redone design touches only `UrlValidator`, and is approved.
3. **Safe-stop and resume.** The planning call reports a token surge that exceeds the run's budget. The engine **safe-stops**: the stage
   in flight finishes, nothing new starts, state is kept. Resuming without more budget is refused; the human raises it and resumes.
4. One task (`UrlValidator`, a security-relevant file) is implemented, approved, validated and released.

**Result.** Creation now rejects URLs with embedded credentials, IP-literal hosts and local-only hosts. The rate limiter is untouched
and `pom.xml` has no new dependency (both asserted).

**Validation.** As above, with parameterised tests for each rejection class and for valid URLs that must keep working
(`https://1password.com/`, `https://localhost.example.com/`, a path containing `@`).

---

## Measured results

Each scenario was run through `scripts/run-scenario.ps1 <name> -AutoPilot` against the packaged application
(`java -jar`, development database, 2026-10-20), and again by `ScenarioE2ETest`. Both use the real Maven build and test run inside
the run's workspace. Model time is not included: replayed responses return instantly, so the stage latencies below are the
orchestrator's own overhead plus the build.

| | Greenfield | Brownfield | Ambiguous |
| --- | --- | --- | --- |
| Final status | COMPLETED | COMPLETED | COMPLETED |
| Wall time | 25 s | 58 s | 55 s |
| Stages (skipped) | 12 (1) | 13 (0) | 10 (0) |
| Human checkpoints | 4 approvals | 5 approvals + 1 repeat after rollback | 1 clarification, 1 rejection, 1 approval of each of design / `impl:T1` / release |
| Policy violations blocked | 0 | 1 (`migrations-append-only`) | 0 |
| Retries / fallbacks | 1 / 1 (documentation) | 1 retry (`impl:T3` after the policy block) | 0 / 0 |
| Validation loops / rollbacks | 0 / 0 | 1 / 1 | 0 / 0 |
| Stages redone | none | `impl:T1`, `impl:T2`, `test_generation` | `requirements`, `design` |
| Safe-stops | 0 | 0 | 1 (budget), resumed |
| Tokens (replay estimate) | 15,433 | 51,173 | 169,948 (150,000 of it the injected surge) |

Across the three runs `GET /api/v1/metrics/reliability` reports: run success rate 100% (3 of 3), stage success rate 100%, 2 retries,
1 fallback, 1 rollback, 1 validation fix loop, **MTTR 11.5 s** (3 recoveries), end-to-end latency p50 57 s / p95 58 s, validation
(the real build) averaging 36 s while every other stage averages under a second, 1 policy violation (`migrations-append-only`),
and `GET /api/v1/audit/verify` reports the 210-event chain intact.

### The brownfield branch, as a reviewer sees it

One commit per accepted stage on `agent/<runId>`, and a diff that touches only what the design approved:

```text
0cc9658 [release] Engineering summary for run bcbb3be7-...
a2d6eab [impl:T2] Bulk DTOs and POST /api/v1/urls/bulk
ed97aae [impl:T1] Enforce expiry on every resolve (null-safe, boundary inclusive); add createBatch
0905388 [test_generation] Regression tests for the expiry fix, and tests for batch and bulk creation
1086792 [impl:T4] Apply the create rate limit to the bulk path (replayed)
150bd90 [impl:T3] Add the expires_at index as a new migration (replayed)
676062c [documentation] Document bulk create and the expiry fix (replayed)
3a2c940 baseline

 12 files changed, 389 insertions(+), 22 deletions(-)   (ShortenerService, ShortenerDtos, UrlController, CreateRateLimitFilter,
                                                         V3 migration, four test files, two docs, the engineering summary)
```

"(replayed)" marks the stages that were kept through the rollback: their stored patches were re-applied onto the baseline and
recommitted, not redone by an agent. The decision log of that run, for example, reads (abridged): requirements and impact analysis
accepted; design needs a human (`SCHEMA_CHANGE, API_CHANGE, SECURITY_SENSITIVE_FILE`), approved by the scripted human; `impl:T3`,
`impl:T4` and `impl:T2` each approved; **`REPLANNED`: re-opened `[test_generation, impl:T1, validation, impl:T2, release_readiness,
release]` because validation failed with 10 failing tests**; `impl:T1` and the tests redone (patch v2); `impl:T2` needs approval again
and gets it; validation passes; release approved; run completed.
