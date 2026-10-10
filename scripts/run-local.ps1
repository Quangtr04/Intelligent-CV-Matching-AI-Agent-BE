param([string]$EnvFile = '.env', [switch]$WithInfrastructure)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    $resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
    foreach ($line in Get-Content -LiteralPath $resolvedEnvFile) {
        $trimmed = $line.Trim()
        if (!$trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid environment variable declaration' }
        $value = $parts[1].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($parts[0], $value, 'Process')
    }
    if (!$env:POSTGRES_PASSWORD -or $env:POSTGRES_PASSWORD.StartsWith('replace-')) { throw 'Set POSTGRES_PASSWORD in .env before starting' }
    if ($WithInfrastructure) {
        & docker compose --env-file $resolvedEnvFile up -d --wait --wait-timeout 60 postgres
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL container did not start' }
    }
    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited unsuccessfully; check the application output' }
} finally { Pop-Location }
