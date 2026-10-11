# Builds and starts a throwaway gateway with the e2e mock agent registered, then
# prints a pairing payload the emulator can reach through 10.0.2.2. Ports differ
# from the gateway defaults so a dev gateway on this machine is never touched.
#
#   .\gateway.ps1 -GatewayRepo C:\path\to\ferngeist-acp-gateway
#   .\gateway.ps1 -Stop
param(
    [string]$GatewayRepo = "$PSScriptRoot\..\..\..\..\ferngeist-acp-gateway\rearchitecture",
    [string]$PublicUrl = "http://10.0.2.2:5798",
    [switch]$Stop
)
$ErrorActionPreference = "Stop"
$work = "$PSScriptRoot\.work"
$exe = "$work\ferngeist.exe"

Get-Process ferngeist -ErrorAction SilentlyContinue | Where-Object Path -eq (Resolve-Path $exe -ErrorAction SilentlyContinue) | Stop-Process -Force
if ($Stop) { return }

Remove-Item -Recurse -Force "$work\state", "$work\agent-logs", "$work\agent-state" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$work\state" | Out-Null

Push-Location $GatewayRepo
try { go build -ldflags "-X main.buildVersion=e2e" -o $exe ./cmd/ferngeist } finally { Pop-Location }
if ($LASTEXITCODE) { throw "gateway build failed" }

$env:FERNGEIST_GATEWAY_STATE_DB = "$work\state\gateway.db"
$env:FERNGEIST_GATEWAY_LOG_DIR = "$work\state\logs"
$env:FERNGEIST_GATEWAY_LISTEN_ADDR = "127.0.0.1:5798"
$env:FERNGEIST_GATEWAY_ADMIN_ADDR = "127.0.0.1:5799"
Start-Process $exe -ArgumentList "daemon", "run", "--public-base-url", $PublicUrl -WorkingDirectory $work `
    -WindowStyle Hidden -RedirectStandardOutput "$work\state\gateway.out" -RedirectStandardError "$work\state\gateway.err"

foreach ($i in 1..50) {
    try { Invoke-RestMethod http://127.0.0.1:5799/admin/v1/status | Out-Null; break } catch { Start-Sleep -Milliseconds 200 }
}
# Throws when the daemon never came up, before anything talks to the admin port.
Invoke-RestMethod http://127.0.0.1:5799/admin/v1/status | Out-Null

$python = (Get-Command python).Source
& $exe agents add --name "E2E Mock" --command $python `
    --args (Resolve-Path "$PSScriptRoot\mock_agent.py") --args "--log-dir" --args "$work\agent-logs" --args "--state-dir" --args "$work\agent-state" | Out-Null
if ($LASTEXITCODE) { throw "registering the mock agent failed" }

(Invoke-RestMethod -Method Post http://127.0.0.1:5799/admin/v1/pairings/start).payload
