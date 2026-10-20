# agentic-url-shortener

A governed agentic software-engineering system that plans, executes, validates and documents software changes using a
dependency-graph orchestration engine, plus the URL shortener it works on. One Spring Boot application, one PostgreSQL database.

- **URL shortener** (`com.agentic.shortener`): create, redirect, delete and analyse short links.
- **Orchestrator** (`com.agentic.orchestrator`): takes a requirement and drives it through requirements, impact analysis, design,
  planning, parallel implementation, tests and docs, validation and release readiness. Agents propose; deterministic gates,
  policies and **humans** decide. The result is a git branch for review, never a merge.

Read next: [Architecture](docs/ARCHITECTURE.md)

## Setup

**Prerequisites:** JDK 25 or newer (built and tested on 26), Maven 3.9+, PostgreSQL 16+ (tested on 18), git.

**Java 21:** the project is set to build for Java 25, but the code only needs Java 21 features. On a JDK 21 add
`-Djava.version=21` to every Maven command (for example `mvn -Djava.version=21 spring-boot:run` or `mvn -Djava.version=21 verify`).
This compiles cleanly at Java 21 level, but the tests and the application have only been run on JDK 26. Java 17 and older are not supported.

```bash
# 1. Create the databases (once). Needs a PostgreSQL superuser.
psql -h localhost -U postgres -f scripts/db-init.sql

# 2. Tell the app how to connect (these are the defaults except the password)
export DB_URL=jdbc:postgresql://localhost:5432/agentic
export DB_USERNAME=postgres
export DB_PASSWORD=<your password>            # PowerShell: $env:DB_PASSWORD='<your password>'

# 3. Run. Flyway creates the tables on first start.
mvn spring-boot:run                            # http://localhost:8080
```

Swagger UI: <http://localhost:8080/swagger-ui.html> · health: `/actuator/health` · metrics: `/actuator/prometheus`

> **Before running orchestrator scenarios, commit your work.** Each run works on a `git worktree` of the repository's `HEAD`
> (branch `agent/<runId>`, under `./workspaces`). To run against uncommitted changes, point `ORCHESTRATOR_SOURCE_ROOT` at a copy
> of the repo without its `.git` directory.

## The URL shortener

```bash
# create (Idempotency-Key makes retries safe; customAlias and expiresAt are optional)
curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
     -d '{"url":"https://example.com/some/long/path"}'
# redirect: 302 (not 301, so every click is counted); 404 unknown, 410 expired or deleted
curl -i localhost:8080/<code>
# metadata, delete, analytics (clicks total, per day, top referrers)
curl -s localhost:8080/api/v1/urls/<code>
curl -s -X DELETE localhost:8080/api/v1/urls/<code>
curl -s "localhost:8080/api/v1/urls/<code>/stats?from=2026-01-01T00:00:00Z"
```

Errors are RFC 9457 `application/problem+json`. Creation is rate limited per client IP. Client IPs are stored only as salted hashes.

## The orchestrator

Three scripted scenarios show the system end to end (each is a folder under `scenarios/`):

| Scenario | Requirement | Shows |
| --- | --- | --- |
| `greenfield` | Add a QR-code endpoint for short URLs | parallel tasks and a join, a new dependency needing approval, an injected model timeout ending in a fallback |
| `brownfield` | Expired links still redirect sometimes; add bulk create | impact analysis finding the bug, a blocked migration edit, a failed build triggering rollback and redo, three kinds of high-impact approval |
| `ambiguous` | Make the shortener safer against abuse | a clarification pause and re-plan, a rejected design redone, a budget safe-stop and resume |

```bash
# unattended: a scripted human (audited as "autopilot:scripted-human") answers each checkpoint
./scripts/run-scenario.sh greenfield autopilot          # PowerShell: ./scripts/run-scenario.ps1 greenfield -AutoPilot

# with you as the human: the script stops at each checkpoint and prints the commands to answer it
./scripts/run-scenario.sh brownfield
```

Your own requirement, and the human decisions (every one needs the `X-Approval-Key` header, default `local-dev-key`):

```bash
curl -s -X POST localhost:8080/api/v1/runs -H 'Content-Type: application/json' -d '{"requirement":"..."}'   # needs a live model, see below
curl -s localhost:8080/api/v1/runs/<id>                      # the dependency graph with every stage's state
curl -s localhost:8080/api/v1/approvals                      # what is waiting for a human
curl -s -X POST localhost:8080/api/v1/approvals/<id>/approve -H 'X-Approval-Key: local-dev-key' \
     -H 'Content-Type: application/json' -d '{"comment":"reviewed"}'     # or /reject (a comment is mandatory)
curl -s -X POST localhost:8080/api/v1/runs/<id>/clarifications -H 'X-Approval-Key: local-dev-key' \
     -H 'Content-Type: application/json' -d '{"acceptAssumptions":true}'
curl -s -X POST localhost:8080/api/v1/runs/<id>/stop         # safe-stop; then /resume {"tokenBudget": n}
curl -s -X POST "localhost:8080/api/v1/runs/<id>/rollback?toStage=planning" -H 'X-Approval-Key: local-dev-key'
curl -s localhost:8080/api/v1/runs/<id>/decisions            # who decided what, and why
curl -s localhost:8080/api/v1/audit/verify                   # recompute the tamper-evident audit chain
curl -s localhost:8080/api/v1/metrics/reliability            # success rate, retries, rollbacks, MTTR, latency
```

When a run completes, review and merge the branch yourself: `git log agent/<runId>`, `git diff main...agent/<runId>`. The engineering
summary is committed on it at `docs/runs/<runId>/ENGINEERING_SUMMARY.md`.

### Model modes (`ORCHESTRATOR_LLM_MODE`)

| Mode | What happens | Needs |
| --- | --- | --- |
| `replay` (default) | Serves recorded or scripted responses from `scenarios/<name>/responses`; deterministic | nothing, no API key |
| `live` | Calls the Anthropic API (`claude-opus-5-5` by default, `ORCHESTRATOR_LLM_MODEL` to change) | `ANTHROPIC_API_KEY` (or an `ant auth login` profile) |
| `record` | Like `live`, and saves each response so the run can be replayed later | same as `live` |

Free-text requirements (`POST /api/v1/runs` without a scenario) need `live` mode; `replay` only knows the scenarios.
In `live` and `record` modes starting a run costs money, so it needs the `X-Approval-Key` header too.
The `live` adapter is tested against a fake Messages API but has **not** been run against the real service.

## Configuration

Environment variables (defaults in `src/main/resources/application.yml`):

| Variable | Default | Meaning |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/agentic`, `postgres`, `postgres` | database; **change the password** |
| `PORT`, `SHORTENER_BASE_URL` | `8080`, `http://localhost:8080` | server port; base of the short URLs |
| `SHORTENER_IP_SALT` | `change-me` | salt for hashing client IPs; **set your own** |
| `ORCHESTRATOR_APPROVAL_API_KEY` | `local-dev-key` | key for human decisions; **set your own** |
| `ORCHESTRATOR_LLM_MODE`, `ORCHESTRATOR_LLM_MODEL` | `replay`, `claude-opus-5-5` | see above |
| `ORCHESTRATOR_WORKSPACE_ROOT`, `ORCHESTRATOR_SOURCE_ROOT` | `./workspaces`, `.` | where run workspaces go; what they are created from |

## Tests

```bash
mvn test                       # unit, integration and architecture tests; needs the agentic_test database (scripts/db-init.sql)
mvn verify -Pscenarios         # also runs the three scenarios end to end, with a real Maven build inside each run (a few minutes)
```

The test suite uses the database named by `TEST_DB_URL` (default `jdbc:postgresql://localhost:5432/agentic_test`) and the same
`DB_USERNAME` / `DB_PASSWORD`. It truncates tables, so never point it at real data.
