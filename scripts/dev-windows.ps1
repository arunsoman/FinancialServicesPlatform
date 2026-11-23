<#
.SYNOPSIS
    FSP dev commands for native Windows (make-equivalent, no Docker Desktop).

.DESCRIPTION
    Mirrors the repo Makefile for Windows terminals. Container engine is auto-detected:
    podman first, then docker (works with both Podman Desktop and Rancher Desktop).
    TigerBeetle runs as a CONTAINER on Windows (the repo binary is a Linux ELF) with
    seccomp=unconfined + pinned image 0.17.9.

.USAGE
    powershell -ExecutionPolicy Bypass -File scripts\dev-windows.ps1 <command>
    commands: status | infra-up | infra-down | tb-init | tb-start | tb-stop | tb-reset
              stack-up | stack-down | pki | docs-refresh | docs-check
              dev | stop | tune | help
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'infra-up', 'infra-down', 'infra-init', 'tb-init', 'tb-start',
        'tb-stop', 'tb-reset', 'stack-up', 'stack-down', 'pki', 'docs-refresh', 'docs-check',
        'dev', 'stop', 'tune', 'help')]
    [string] $Command = 'help',
    [int]    $MemoryMB = 0,   # 0 = auto-size from this laptop's physical RAM (see Get-FspSizing)
    [int]    $Cpus = 0        # 0 = same
)

$ErrorActionPreference = 'Stop'
$ROOT = Split-Path $PSScriptRoot
$TB_IMAGE = 'ghcr.io/tigerbeetle/tigerbeetle:0.17.9'   # bumps together with build.gradle.kts pin
$TB_NAME = 'fsp-tigerbeetle'
$TB_DATA = Join-Path $ROOT 'infrastructure\tigerbeetle-data'
$COMPOSE = Join-Path $ROOT 'infrastructure\docker-compose.yml'

function Resolve-CLI {
    foreach ($c in 'podman', 'docker') {
        if (Get-Command $c -ErrorAction SilentlyContinue) { return $c }
    }
    throw "No container CLI found. Run scripts\windows-setup.ps1 first (Podman or Rancher Desktop)."
}
$CLI = Resolve-CLI

# Engine-VM sizing that leaves the HOST enough RAM to also run the five JVM services.
#   <= 9  GB total  ->  2 GB / 2 vCPU   (8 GB laptops: only postgres + tigerbeetle in the VM,
#                                        run services natively with scripts\dev-windows.ps1 dev)
#   <= 17 GB total  ->  5 GB / 2 vCPU   (16 GB laptops - comfortable, 'stack-up' fits)
#   >  17 GB total  ->  6 GB / 3 vCPU   (workstation)
function Get-FspSizing {
    $total = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    if ($total -le 9)  { return @{ MB = 2048; Cpu = 2; Total = $total } }
    if ($total -le 17) { return @{ MB = 5120; Cpu = 2; Total = $total } }
    return @{ MB = 6144; Cpu = 3; Total = $total }
}

function Invoke-Compose {
    param([string[]] $ArgsCompose)
    if ($CLI -eq 'podman') {
        $composeProvider = Join-Path $env:LOCALAPPDATA 'FSP\bin\docker-compose.exe'
        if (Test-Path $composeProvider) {
            $previousProvider = $env:PODMAN_COMPOSE_PROVIDER
            try {
                $env:PODMAN_COMPOSE_PROVIDER = $composeProvider
                podman compose -f $COMPOSE @ArgsCompose
            } finally {
                $env:PODMAN_COMPOSE_PROVIDER = $previousProvider
            }
        } elseif (Get-Command podman-compose -ErrorAction SilentlyContinue) {
            $previousProvider = $env:PODMAN_COMPOSE_PROVIDER
            try {
                $env:PODMAN_COMPOSE_PROVIDER = (Get-Command podman-compose).Source
                podman compose -f $COMPOSE @ArgsCompose
            } finally {
                $env:PODMAN_COMPOSE_PROVIDER = $previousProvider
            }
        } else {
            throw "Podman Compose provider not found. Run scripts\windows-setup.ps1 or install podman-compose."
        }
    } else {
        docker compose -f $COMPOSE @ArgsCompose
    }
}

switch ($Command) {
    'help' {
        Write-Host "FSP dev on Windows - make-equivalents (engine: $CLI)" -ForegroundColor Magenta
        $help = @(
            '  status          engine + port overview',
            '  infra-up        start PostgreSQL (container)',
            '  infra-down      stop PostgreSQL',
            '  tb-init         format TigerBeetle data file (container, seccomp unconfined)',
            '  tb-start        start TigerBeetle 0.17.9 on 127.0.0.1:3000 (container)',
            '  tb-stop         stop TigerBeetle container',
            '  tb-reset        wipe TigerBeetle data',
            '  stack-up        build + run core/idp/gateway/bff in containers',
            '  stack-down      stop the container stack',
            '  pki             regenerate dev PKI (bash -> Git for Windows)',
            '  docs-refresh    regenerate docs/generated artifacts',
            '  docs-check      fail if docs artifacts are stale (CI gate)',
            '  dev             ONE COMMAND: engine + postgres + tigerbeetle + idp/core/gateway/bff',
            '                  (+kyc plugin) + SPA, each in its own terminal window',
            '  stop            reverse of dev: stop SPA daemons, services, tigerbeetle, postgres',
            '  tune            resize the engine VM (sufficient: 5 GB RAM / 2 vCPU;',
            '                  pass -MemoryMB / -Cpus to override)'
        )
        Write-Host ($help -join [Environment]::NewLine)
        break
    }

    'status' {
        Write-Host "engine: $CLI $(( & $CLI --version | Select-Object -First 1 ))" -ForegroundColor Cyan
        & $CLI ps -a --filter "name=fsp-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>$null
        foreach ($p in 5432, 3000, 8081, 8082, 8090, 8080, 8083, 5173) {
            $busy = (Test-NetConnection -ComputerName 127.0.0.1 -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded
            Write-Host ("port {0,-5} {1}" -f $p, $(if ($busy) { 'OPEN' } else { 'closed' }))
        }
        break
    }

    'infra-up' {
        Invoke-Compose @('up', '-d')
        Start-Sleep -Seconds 3
        Invoke-Compose @('ps')
        break
    }

    'infra-down' { Invoke-Compose @('down'); break }

    'infra-init' { New-Item -ItemType Directory -Force -Path $TB_DATA | Out-Null; break }

    'tb-init' {
        $oldPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $CLI run --rm --user 0:0 `
            --security-opt seccomp=unconfined `
            -v "${TB_DATA}:/data" `
            --entrypoint /tigerbeetle $TB_IMAGE `
            format --cluster=0 --replica=0 --replica-count=1 /data/0_0.tigerbeetle 2>$null
        $formatExit = $LASTEXITCODE
        $ErrorActionPreference = $oldPref
        if ($formatExit -ne 0) { Write-Host "[warn] format skipped/failed (exit $formatExit) - usually 'already formatted'" -ForegroundColor DarkYellow }
        break
    }

    'tb-start' {
        & $PSScriptRoot\dev-windows.ps1 tb-init | Out-Null
        $existing = & $CLI ps -a --filter "name=$TB_NAME" --format "{{.ID}}" 2>$null
        if ($existing) {
            $state = (& $CLI ps -a --filter "name=$TB_NAME" --format "{{.State}}" | Select-Object -First 1)
            if ($state -eq 'Running') { Write-Host "[ ok  ] TigerBeetle already running"; break }
            & $CLI start $TB_NAME | Out-Null; Write-Host "[ ok  ] TigerBeetle (re)started"; break
        }
        & $CLI run -d --name $TB_NAME `
            -p 127.0.0.1:3000:3000 `
            --security-opt seccomp=unconfined `
            --ulimit nofile=65536:65536 `
            -v "${TB_DATA}:/data" `
            --entrypoint /tigerbeetle $TB_IMAGE `
            start --addresses=0.0.0.0:3000 /data/0_0.tigerbeetle | Out-Null
        Write-Host "[ ok  ] TigerBeetle container started on 127.0.0.1:3000 (data: infrastructure\tigerbeetle-data)"
        break
    }

    'tb-stop' {
        & $CLI stop $TB_NAME 2>$null | Out-Null
        & $CLI rm $TB_NAME 2>$null | Out-Null
        Write-Host "[ ok  ] TigerBeetle stopped/removed"
        break
    }

    'tb-reset' {
        & $PSScriptRoot\dev-windows.ps1 tb-stop
        Remove-Item $TB_DATA -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $TB_DATA | Out-Null
        & $PSScriptRoot\dev-windows.ps1 tb-init
        Write-Host "[ ok  ] TigerBeetle data reset - run dev-windows.ps1 tb-start"
        break
    }

    'stack-up' {
        Invoke-Compose @('--profile', 'stack', 'up', '-d', '--build', 'core', 'idp', 'gateway', 'bff')
        break
    }

    'stack-down' { Invoke-Compose @('--profile', 'stack', 'down'); break }

    'pki' {
        $bashExe = "${env:ProgramFiles}\Git\bin\bash.exe"
        if (-not (Test-Path $bashExe)) { throw "Git Bash not found - install Git for Windows (scripts\windows-setup.ps1 does it)." }
        & $bashExe -lc "cd '$ROOT' && bash infrastructure/pki/dev-pki.sh"
        Write-Host "[ ok  ] dev PKI regenerated (gitignored)"
        break
    }

    'docs-refresh' { node "$ROOT\docs\gen\refresh.cjs"; break }
    'docs-check'   { node "$ROOT\docs\gen\refresh.cjs" --check; break }

    'tune' {
        # Resize the engine VM. Defaults (0/0) auto-size from the laptop's physical RAM;
        # pass -MemoryMB / -Cpus to override.
        $size = Get-FspSizing
        if ($MemoryMB -gt 0) { $size.MB = $MemoryMB }
        if ($Cpus -gt 0)     { $size.Cpu = $Cpus }
        Write-Host "[ info] laptop has ~$($size.Total) GB RAM -> sizing engine VM to $($size.MB) MB / $($size.Cpu) vCPU" -ForegroundColor Cyan
        if ($size.Total -le 9) {
            Write-Host "[ warn] 8 GB-class laptop: keep the VM small (postgres + tigerbeetle only) and run the" -ForegroundColor Yellow
            Write-Host "        services natively via 'scripts\dev-windows.ps1 dev'. Avoid 'stack-up' (five JVM" -ForegroundColor Yellow
            Write-Host "        containers will not fit in 2 GB) and close browsers/IDEs while building." -ForegroundColor Yellow
        }
        if ($CLI -eq 'podman') {
            Write-Host "[step ] stopping podman machine to resize..." -ForegroundColor Yellow
            & $CLI machine stop | Out-Null
            & $CLI machine set --memory $size.MB --cpus $size.Cpu | Out-Null
            & $CLI machine start | Out-Null
            Write-Host "[ ok  ] podman machine resized to $($size.MB) MB / $($size.Cpu) vCPU"
        } else {
            $rd = Join-Path $env:APPDATA 'rancher-desktop\settings.json'
            if (Test-Path $rd) {
                $json = Get-Content $rd -Raw | ConvertFrom-Json
                $json | Add-Member -NotePropertyName 'virtualMachine' -NotePropertyValue @{ memoryInGB = [math]::Ceiling($size.MB / 1024.0); numberCPUs = $size.Cpu } -Force
                $json | ConvertTo-Json -Depth 10 | Set-Content $rd -Encoding utf8
                Write-Host "[ ok  ] wrote $($size.MB) MB / $($size.Cpu) vCPU to settings.json - RESTART Rancher Desktop to apply"
                Write-Host "         (or use its UI: Preferences > Virtual Machine > Memory (GB) / Number CPUs)"
            } else {
                Write-Warning "Rancher settings.json not found - use the UI: Preferences > Virtual Machine"
            }
        }
        break
    }

    'dev' {
        # ONE COMMAND: infrastructure + five backend services + SPA, each in its own terminal.
        Write-Host "=== FSP dev up (engine: $CLI) ===" -ForegroundColor Magenta
        & $PSScriptRoot\dev-windows.ps1 infra-up
        & $PSScriptRoot\dev-windows.ps1 tb-start

        $services = @(
            @{ Name = 'idp';        Dir = Join-Path $ROOT 'backend\idp';         Rel = '..\gradlew.bat bootRun' },
            @{ Name = 'core';       Dir = Join-Path $ROOT 'backend\core';        Rel = '..\gradlew.bat bootRun' },
            @{ Name = 'gateway';    Dir = Join-Path $ROOT 'backend\gateway';     Rel = '..\gradlew.bat bootRun' },
            @{ Name = 'bff';        Dir = Join-Path $ROOT 'backend\bff';         Rel = '..\gradlew.bat bootRun' },
            @{ Name = 'plugin-kyc'; Dir = Join-Path $ROOT 'backend\plugins\kyc'; Rel = '..\..\gradlew.bat bootRun' }
        )
        foreach ($s in $services) {
            $cmd = "Set-Location -Path '$($s.Dir)'; $($s.Rel)"
            Start-Process powershell -ArgumentList '-NoExit', '-Command', $cmd -WindowStyle Normal
            Write-Host "[ up  ] $($s.Name) launched in its own terminal"
            Start-Sleep -Seconds 2   # stagger boots; keep the stated order
        }
        $feCmd = "Set-Location -Path '$(Join-Path $ROOT 'frontend')'; bun run dev"
        Start-Process powershell -ArgumentList '-NoExit', '-Command', $feCmd -WindowStyle Normal
        Write-Host "[ up  ] SPA (vite :5173) launched in its own terminal"
        Write-Host ""
        Write-Host "Smoke: GET http://127.0.0.1:8080/bff/csrf -> POST /bff/auth/login (platform-admin/fsp-admin)" -ForegroundColor Cyan
        Write-Host "SPA:   http://localhost:5173   |  workbench: http://localhost:5173/widget-workbench/" -ForegroundColor Cyan
        Write-Host "Stop everything with: scripts\dev-windows.ps1 stop" -ForegroundColor Cyan
        break
    }

    'stop' {
        # Reverse of 'dev' - gradle daemons, SPA dev server, TB, postgres.
        # One gradlew for the whole backend (see Makefile: cd backend/<svc> && ../gradlew) -
        # `gradlew --stop` stops all daemons of this gradle installation.
        & (Join-Path $ROOT 'backend\gradlew.bat') --stop 2>$null | Out-Null
        # SPA: find the vite dev server on :5173 and stop the owning process tree
        $vite = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($vite) { Stop-Process -Id $vite.OwningProcess -Force -ErrorAction SilentlyContinue; Write-Host "[down] SPA (vite) stopped" }
        # close leftover java processes on FSP ports (daemons already stopped above)
        foreach ($p in 8081, 8082, 8090, 8080, 8083) {
            $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
        }
        & $PSScriptRoot\dev-windows.ps1 tb-stop
        & $PSScriptRoot\dev-windows.ps1 infra-down
        Write-Host "[ ok  ] everything stopped - scripts\dev-windows.ps1 dev to bring it all back"
        break
    }
}
