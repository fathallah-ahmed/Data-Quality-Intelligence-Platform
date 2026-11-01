<#
.SYNOPSIS
  Starts the platform for development, each service in its own PowerShell window.

.DESCRIPTION
  Ollama (when installed and not running), Airflow (docker compose), the backend and the
  frontend. When everything answers, the app opens in the browser. Close a window to stop its
  service; stop Airflow with `docker compose down` in Data_Quality_Wevioo.

  To run everything in containers instead, use the root docker-compose.yml:
    docker compose --env-file Data_Quality_Wevioo/.env up --build

.PARAMETER NoOllama
  Do not start Ollama (the analysis then uses deterministic insights only).

.PARAMETER NoAirflow
  Do not start Airflow (uploads will fail until it runs).

.PARAMETER NoBrowser
  Do not open the browser when the services are ready.
#>
param(
    [switch] $NoOllama,
    [switch] $NoAirflow,
    [switch] $NoBrowser
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$worker = Join-Path $root 'Data_Quality_Wevioo'

function Test-Port([int] $port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        $open = $async.AsyncWaitHandle.WaitOne(500) -and $client.Connected
        $client.Close()
        return $open
    } catch {
        return $false
    }
}

function Start-Window([string] $title, [string] $directory, [string] $command) {
    $script = "`$Host.UI.RawUI.WindowTitle = '$title'; Set-Location '$directory'; $command"
    Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-Command', $script | Out-Null
    Write-Host "  started: $title"
}

function Wait-Until([string] $name, [scriptblock] $check, [int] $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $check) {
            Write-Host "  ready:   $name"
            return $true
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "  waiting for $name took longer than $seconds s; check its window." -ForegroundColor Yellow
    return $false
}

Write-Host 'Data Quality platform' -ForegroundColor Cyan

# --- Ollama -------------------------------------------------------------------------------
if (-not $NoOllama) {
    if (Test-Port 11434) {
        Write-Host '  ollama already running'
    } elseif (Get-Command ollama -ErrorAction SilentlyContinue) {
        Start-Window 'Ollama' $root 'ollama serve'
    } else {
        Write-Host '  ollama is not installed: insights will be deterministic only.' -ForegroundColor Yellow
    }
}

# --- Airflow ------------------------------------------------------------------------------
if (-not $NoAirflow) {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  Docker is not running. Start Docker Desktop, or pass -NoAirflow.' -ForegroundColor Red
        exit 1
    }
    if (-not (Test-Path (Join-Path $worker '.env'))) {
        Copy-Item (Join-Path $worker '.env.example') (Join-Path $worker '.env')
        Write-Host '  created Data_Quality_Wevioo\.env from .env.example: change its secrets before sharing.' -ForegroundColor Yellow
    }
    if (Test-Port 8080) {
        Write-Host '  airflow already running'
    } else {
        Start-Window 'Airflow' $worker 'docker compose up --build'
    }
}

# --- Neo4j (the graph the Graph tab builds) -------------------------------------------------
if (-not $NoAirflow) {
    if (Test-Port 7687) {
        Write-Host '  neo4j already running'
    } else {
        docker compose -f (Join-Path $root 'docker-compose.neo4j.yml') up -d *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host '  started: Neo4j (http://localhost:7474, user neo4j)'
        } else {
            Write-Host '  Neo4j could not be started: the Graph tab will say so. Run docker compose -f docker-compose.neo4j.yml up -d to see why.' -ForegroundColor Yellow
        }
    }
}

# --- Backend and frontend -----------------------------------------------------------------
if (Test-Port 8081) {
    Write-Host '  backend already running'
} else {
    Start-Window 'Backend' (Join-Path $root 'backend') '.\run-backend.ps1'
}

$frontend = Join-Path $root 'frontend'
if (Test-Port 4200) {
    Write-Host '  frontend already running'
} else {
    $install = if (Test-Path (Join-Path $frontend 'node_modules')) { '' } else { 'npm install; ' }
    Start-Window 'Frontend' $frontend "${install}npm start"
}

# --- Wait, then open ----------------------------------------------------------------------
$backendUp = Wait-Until 'backend  (http://localhost:8081)' { Test-Port 8081 } 180
$frontendUp = Wait-Until 'frontend (http://localhost:4200)' { Test-Port 4200 } 240
if (-not $NoAirflow) {
    Write-Host '  airflow  (http://localhost:8080) can take a few more minutes the first time.'
}

if ($backendUp -and $frontendUp -and -not $NoBrowser) {
    Start-Process 'http://localhost:4200'
}
Write-Host 'Sign in with admin / admin123 (development defaults).' -ForegroundColor Green
