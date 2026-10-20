<#
.SYNOPSIS
  Runs one of the demo scenarios (greenfield, brownfield, ambiguous) against a running instance and prints what happened.

.DESCRIPTION
  Starts the scenario through the REST API and polls the run until it finishes or needs a human.
  With -AutoPilot a scripted human (audited as "autopilot:scripted-human") answers the checkpoints, so the whole run goes
  through unattended. Without it the script stops whenever a human decision is needed and prints the commands to give it.

.EXAMPLE
  ./scripts/run-scenario.ps1 greenfield -AutoPilot
  ./scripts/run-scenario.ps1 ambiguous
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('greenfield', 'brownfield', 'ambiguous')][string]$Scenario,
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApprovalKey = $(if ($env:ORCHESTRATOR_APPROVAL_API_KEY) { $env:ORCHESTRATOR_APPROVAL_API_KEY } else { 'local-dev-key' }),
    [switch]$AutoPilot,
    [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
$auto = if ($AutoPilot) { 'true' } else { 'false' }
$run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/scenarios/$Scenario/run?autopilot=$auto"
$id = $run.id
Write-Host "Started $Scenario run $id (branch $($run.branch))"

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$last = ''
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $detail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/runs/$id"
    $state = "$($detail.run.status) | " + (($detail.stages | ForEach-Object { "$($_.key)=$($_.status)" }) -join ' ')
    if ($state -ne $last) {
        Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $detail.run.status) -NoNewline
        Write-Host (" {0}" -f $detail.run.statusReason)
        $last = $state
    }
    $status = $detail.run.status
    if ($status -in 'COMPLETED', 'FAILED') { break }
    if (-not $AutoPilot -and $status -in 'PAUSED', 'STOPPED') {
        $pending = Invoke-RestMethod -Uri "$BaseUrl/api/v1/approvals?status=PENDING"
        Write-Host "`nA human decision is needed:" -ForegroundColor Yellow
        foreach ($a in $pending | Where-Object { $_.runId -eq $id }) {
            Write-Host "  approval $($a.id) for stage '$($a.stageKey)': $($a.reasons)"
            Write-Host "    approve: Invoke-RestMethod -Method Post -Uri $BaseUrl/api/v1/approvals/$($a.id)/approve -Headers @{'X-Approval-Key'='$ApprovalKey'} -ContentType application/json -Body '{""comment"":""reviewed""}'"
            Write-Host "    reject:  Invoke-RestMethod -Method Post -Uri $BaseUrl/api/v1/approvals/$($a.id)/reject  -Headers @{'X-Approval-Key'='$ApprovalKey'} -ContentType application/json -Body '{""comment"":""why""}'"
        }
        foreach ($c in $detail.clarifications | Where-Object { -not $_.answer }) {
            Write-Host "  question $($c.id): $($c.question)  (proposed assumption: $($c.proposedAssumption))"
        }
        if ($detail.clarifications | Where-Object { -not $_.answer }) {
            Write-Host "    answer: POST $BaseUrl/api/v1/runs/$id/clarifications  {""acceptAssumptions"": true}  (header X-Approval-Key)"
        }
        if ($status -eq 'STOPPED') {
            Write-Host "  stopped: $($detail.run.statusReason). Resume: POST $BaseUrl/api/v1/runs/$id/resume  {""tokenBudget"": 2000000}  (header X-Approval-Key)"
        }
        Write-Host "Re-run this script's polling by calling: Invoke-RestMethod $BaseUrl/api/v1/runs/$id"
        break
    }
}

$detail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/runs/$id"
Write-Host "`nFinal status: $($detail.run.status)  tokens used: $($detail.run.tokensUsed)  policy violations: $($detail.run.policyViolations)"
$detail.stages | Format-Table key, agent, status, attempt, generation -AutoSize
Write-Host "Branch with the result: $($detail.run.branch)  (workspace: $($detail.run.workspacePath))"
Write-Host "Decision log:  $BaseUrl/api/v1/runs/$id/decisions"
Write-Host "Audit trail:   $BaseUrl/api/v1/runs/$id/audit   (verify chain: $BaseUrl/api/v1/audit/verify)"
Write-Host "Metrics:       $BaseUrl/api/v1/metrics/reliability"
