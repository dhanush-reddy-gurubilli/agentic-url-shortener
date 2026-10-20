#!/usr/bin/env bash
# Runs one of the demo scenarios (greenfield | brownfield | ambiguous) against a running instance.
#   ./scripts/run-scenario.sh greenfield autopilot
#   ./scripts/run-scenario.sh ambiguous            # stops whenever a human decision is needed
# With "autopilot" a scripted human (audited as "autopilot:scripted-human") answers the checkpoints.
set -euo pipefail

scenario="${1:?usage: run-scenario.sh <greenfield|brownfield|ambiguous> [autopilot]}"
auto="false"; [[ "${2:-}" == "autopilot" ]] && auto="true"
base="${BASE_URL:-http://localhost:8080}"
key="${ORCHESTRATOR_APPROVAL_API_KEY:-local-dev-key}"

run=$(curl -fsS -X POST "$base/api/v1/scenarios/$scenario/run?autopilot=$auto")
id=$(echo "$run" | sed -E 's/.*"id":"([0-9a-f-]{36})".*/\1/')
echo "Started $scenario run $id"

for _ in $(seq 1 900); do
  sleep 2
  detail=$(curl -fsS "$base/api/v1/runs/$id")
  # "run" is the first object, so its status is the first "status" in the document (requirement text may contain braces)
  status=$(echo "$detail" | grep -o '"status":"[A-Z_]*"' | head -1 | cut -d'"' -f4)
  echo "[$(date +%H:%M:%S)] $status"
  [[ "$status" == "COMPLETED" || "$status" == "FAILED" ]] && break
  if [[ "$auto" == "false" && ( "$status" == "PAUSED" || "$status" == "STOPPED" ) ]]; then
    echo "A human decision is needed. Pending approvals:"
    curl -fsS "$base/api/v1/approvals?status=PENDING"; echo
    echo "$detail" | grep -o '"question":"[^"]*"' | sed -E 's/"question":"([^"]*)"/Question: \1/'
    echo "Approve:  curl -X POST $base/api/v1/approvals/<id>/approve -H 'X-Approval-Key: $key' -H 'Content-Type: application/json' -d '{\"comment\":\"reviewed\"}'"
    echo "Reject:   curl -X POST $base/api/v1/approvals/<id>/reject  -H 'X-Approval-Key: $key' -H 'Content-Type: application/json' -d '{\"comment\":\"why\"}'"
    echo "Answer:   curl -X POST $base/api/v1/runs/$id/clarifications -H 'X-Approval-Key: $key' -H 'Content-Type: application/json' -d '{\"acceptAssumptions\":true}'"
    echo "Resume:   curl -X POST $base/api/v1/runs/$id/resume -H 'X-Approval-Key: $key' -H 'Content-Type: application/json' -d '{\"tokenBudget\":2000000}'"
    break
  fi
done

echo "$detail" | grep -o '"key":"[a-z_:A-Z0-9]*","agent":"[A-Z_]*","status":"[A-Z_]*"' \
  | sed -E 's/"key":"([^"]*)","agent":"([^"]*)","status":"([^"]*)"/  \1  \2  \3/'
echo "Branch with the result: agent/$id"
echo "Decision log: $base/api/v1/runs/$id/decisions"
echo "Audit trail:  $base/api/v1/runs/$id/audit   (verify: $base/api/v1/audit/verify)"
echo "Metrics:      $base/api/v1/metrics/reliability"
