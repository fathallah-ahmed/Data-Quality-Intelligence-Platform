$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$airflowEnvPath = Join-Path $backendRoot "..\Data_Quality_Wevioo\.env"

if (-not (Test-Path $airflowEnvPath)) {
    throw "Airflow .env not found at $airflowEnvPath"
}

function Get-DotEnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $line = Get-Content $Path | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -First 1
    if (-not $line) {
        return $null
    }

    return $line.Substring($line.IndexOf("=") + 1).Trim().Trim('"').Trim("'")
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
}
if (-not $javaHome) {
    $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
}
if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    $javaBin = Join-Path $javaHome "bin"
    if (Test-Path $javaBin) {
        $env:Path = "$javaBin;$env:Path"
    }
}

$airflowPassword = Get-DotEnvValue -Path $airflowEnvPath -Name "AIRFLOW_ADMIN_PASSWORD"
$pipelineToken = Get-DotEnvValue -Path $airflowEnvPath -Name "PIPELINE_API_TOKEN"

if (-not $airflowPassword) {
    throw "AIRFLOW_ADMIN_PASSWORD is missing in $airflowEnvPath"
}
if (-not $pipelineToken) {
    throw "PIPELINE_API_TOKEN is missing in $airflowEnvPath"
}

$env:AIRFLOW_USERNAME = "admin"
$env:AIRFLOW_PASSWORD = $airflowPassword
$env:PIPELINE_API_TOKEN = $pipelineToken

Set-Location $backendRoot
Write-Host "Starting backend with Airflow credentials and pipeline token from Data_Quality_Wevioo\.env"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
.\mvnw.cmd spring-boot:run
