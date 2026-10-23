param(
    [string]$EnvFile = '.env',
    [switch]$ExposeToHost,
    [switch]$Gpu,
    [switch]$SmokeTest
)

$ErrorActionPreference = 'Stop'
if ($SmokeTest -and !$ExposeToHost) {
    throw 'Use -ExposeToHost with -SmokeTest; the base stack does not publish Ollama to the host.'
}

$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    $resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
    $composeArguments = @('compose', '--env-file', $resolvedEnvFile, '-f', 'compose.yaml')
    if ($ExposeToHost) {
        $composeArguments += @('-f', 'compose.ollama-host.yaml')
    }
    if ($Gpu) {
        $composeArguments += @('-f', 'compose.ollama-gpu.yaml')
    }
    $composeArguments += @('--profile', 'ai')

    & docker @composeArguments up -d --wait --wait-timeout 120 ollama
    if ($LASTEXITCODE -ne 0) {
        if ($Gpu) {
            Write-Host 'If NVIDIA GPU passthrough is unavailable, rerun without -Gpu to use the portable CPU configuration.'
        }
        throw 'Ollama server did not become healthy; check Docker Desktop and docker compose logs ollama.'
    }

    Write-Host 'Preparing local chat, assessment and embedding models. First download can take several minutes.'
    & docker @composeArguments up -d ollama-init
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start Ollama model initialization.'
    }
    $initContainers = @(& docker @composeArguments ps --all --quiet ollama-init)
    if ($LASTEXITCODE -ne 0 -or $initContainers.Count -ne 1) {
        throw 'Could not locate the Ollama initialization container.'
    }
    $initExitCode = & docker wait $initContainers[0]
    if ($LASTEXITCODE -ne 0 -or $initExitCode -ne '0') {
        & docker @composeArguments logs --tail 40 ollama-init
        throw 'Model download failed; Ollama inference has not been verified.'
    }

    Write-Host 'Ollama server is healthy and all configured local model manifests are present.'
    if ($SmokeTest) {
        & (Join-Path $PSScriptRoot 'test-ollama.ps1') -EnvFile $resolvedEnvFile -ColdStart
    } else {
        Write-Host 'Inference has not been tested. Use -ExposeToHost -SmokeTest for chat and embedding checks.'
    }
} finally {
    Pop-Location
}
