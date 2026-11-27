<#
.SYNOPSIS
    One-click launcher for FSP dev on Windows.

.DESCRIPTION
    Runs scripts\dev-windows.ps1 dev with the correct execution policy
    and working directory so you can launch the whole stack (PostgreSQL,
    TigerBeetle, IDP, Core, Gateway, BFF, KYC plugin, and the SPA) with a
    single command — no need to type the full PowerShell invocation.

.USAGE
    Right-click → "Run with PowerShell"
    OR
    powershell -File start-fsp-dev.ps1
#>
[CmdletBinding()]
param(
    [switch] $NoStack   # if set, skips infra/TB/stack and only runs the SPA
)

$ErrorActionPreference = 'Stop'

# Ensure UTF-8 console output on Windows (chcp 65001 equivalent)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new()

# Run from repo root regardless of where the user launches from
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $SCRIPT_DIR

# Bypass execution policy for this session so the script can run
$env:PSExecutionPolicyPreference = 'Bypass'

Write-Host "=== FSP Dev Launcher ===" -ForegroundColor Magenta
Write-Host "Repo root: $SCRIPT_DIR" -ForegroundColor Cyan

$devScript = Join-Path $SCRIPT_DIR 'scripts\dev-windows.ps1'

if ($NoStack) {
    Write-Host "Launching SPA only..." -ForegroundColor Yellow
    Start-Process powershell `
        -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', "Set-Location -Path '$SCRIPT_DIR\frontend'; bun run dev" `
        -WindowStyle Normal
} else {
    Write-Host "Launching full dev stack (infra + TigerBeetle + 5 services + SPA)..." -ForegroundColor Yellow
    Start-Process powershell `
        -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-File', $devScript, 'dev' `
        -WindowStyle Normal
}
