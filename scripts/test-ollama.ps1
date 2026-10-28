param(
    [string]$EnvFile = '.env',
    [string]$BaseUrl,
    [string]$ChatModel,
    [string]$EmbeddingModel,
    [ValidateRange(1, 65536)]
    [int]$ExpectedDimensions = 1024,
    [ValidateRange(10, 3600)]
    [int]$TimeoutSeconds = 600,
    [switch]$ColdStart,
    [string]$OutputFile = 'target/ollama-smoke.json'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    $fileSettings = @{}
    if (Test-Path -LiteralPath $EnvFile) {
        foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
            if ($line -match '^\s*(AI_CHAT_MODEL|AI_EMBEDDING_MODEL|OLLAMA_PORT)\s*=(.*)$') {
                $fileSettings[$Matches[1]] = $Matches[2].Trim()
            }
        }
    }

    function Get-OllamaSetting([string]$Name, [string]$Fallback) {
        $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
        if (![string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
        if ($fileSettings.ContainsKey($Name) -and $fileSettings[$Name]) { return $fileSettings[$Name] }
        return $Fallback
    }

    if (!$BaseUrl) { $BaseUrl = 'http://127.0.0.1:' + (Get-OllamaSetting 'OLLAMA_PORT' '11434') }
    if (!$ChatModel) { $ChatModel = Get-OllamaSetting 'AI_CHAT_MODEL' 'qwen2.5:7b' }
    if (!$EmbeddingModel) { $EmbeddingModel = Get-OllamaSetting 'AI_EMBEDDING_MODEL' 'qwen3-embedding:0.6b' }
    $BaseUrl = $BaseUrl.TrimEnd('/')
    if ($ChatModel -match ':cloud$' -or $EmbeddingModel -match ':cloud$') {
        throw 'The smoke test requires downloaded local models, not Ollama cloud models.'
    }

    function Invoke-OllamaRequest([string]$Path, [hashtable]$Body) {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-RestMethod -Uri ($BaseUrl + $Path) -Method Post `
            -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
            -TimeoutSec $TimeoutSeconds
        $watch.Stop()
        return @{ Response = $response; WallMilliseconds = [math]::Round($watch.Elapsed.TotalMilliseconds, 3) }
    }

    $version = Invoke-RestMethod -Uri ($BaseUrl + '/api/version') -TimeoutSec 10
    $tags = Invoke-RestMethod -Uri ($BaseUrl + '/api/tags') -TimeoutSec 10
    $knownModels = @($tags.models | ForEach-Object { $_.name })
    foreach ($model in @($ChatModel, $EmbeddingModel)) {
        if ($knownModels -notcontains $model) {
            throw "Local model '$model' is missing. Run scripts/start-ollama.ps1 first."
        }
    }

    if ($ColdStart) {
        Write-Host 'Unloading the selected models for a cold first inference; run this outside active user traffic.'
        foreach ($model in @($ChatModel, $EmbeddingModel)) {
            $null = Invoke-OllamaRequest '/api/generate' @{
                model = $model; prompt = ''; stream = $false; keep_alive = '0'
            }
        }
        $unloaded = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            $running = Invoke-RestMethod -Uri ($BaseUrl + '/api/ps') -TimeoutSec 10
            $selected = @($running.models | Where-Object { $_.name -in @($ChatModel, $EmbeddingModel) })
            if ($selected.Count -eq 0) { $unloaded = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (!$unloaded) { throw 'Selected models are still loaded; cannot label the first requests as cold.' }
    }

    $results = New-Object 'System.Collections.Generic.List[object]'
    $firstPhase = if ($ColdStart) { 'cold' } else { 'first' }
    foreach ($phase in @($firstPhase, 'warm')) {
        $call = Invoke-OllamaRequest '/api/chat' @{
            model = $ChatModel
            messages = @(@{ role = 'user'; content = 'Return a JSON object with exactly one field: ok set to true.' })
            format = 'json'
            stream = $false
            keep_alive = '5m'
            options = @{ temperature = 0; num_predict = 48; num_ctx = 2048 }
        }
        $response = $call.Response
        $parsed = $response.message.content | ConvertFrom-Json
        if ($response.done -isnot [bool] -or !$response.done -or $parsed.ok -isnot [bool] -or !$parsed.ok) {
            throw "Chat $phase response failed the JSON readiness check."
        }
        $results.Add([pscustomobject]@{
            feature = 'chat'; phase = $phase; model = $ChatModel; passed = $true
            wall_ms = $call.WallMilliseconds
            total_ms = [math]::Round([double]$response.total_duration / 1e6, 3)
            load_ms = [math]::Round([double]$response.load_duration / 1e6, 3)
            output_tokens = $response.eval_count
        })
    }

    foreach ($phase in @($firstPhase, 'warm')) {
        $call = Invoke-OllamaRequest '/api/embed' @{
            model = $EmbeddingModel
            input = 'A software engineer builds Java and PostgreSQL applications.'
            truncate = $false
            keep_alive = '5m'
        }
        $response = $call.Response
        if (@($response.embeddings).Count -ne 1) { throw 'Embedding response must contain exactly one vector.' }
        $vector = @($response.embeddings[0])
        if ($vector.Count -ne $ExpectedDimensions) {
            throw "Embedding returned $($vector.Count) dimensions; expected $ExpectedDimensions."
        }
        $squaredNorm = 0.0
        foreach ($component in $vector) {
            $number = [double]$component
            if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) { throw 'Embedding contains a nonfinite value.' }
            $squaredNorm += $number * $number
        }
        if ($squaredNorm -le 0) { throw 'Embedding returned a zero vector.' }
        $results.Add([pscustomobject]@{
            feature = 'embedding'; phase = $phase; model = $EmbeddingModel; passed = $true
            dimensions = $vector.Count
            wall_ms = $call.WallMilliseconds
            total_ms = [math]::Round([double]$response.total_duration / 1e6, 3)
            load_ms = [math]::Round([double]$response.load_duration / 1e6, 3)
            norm = [math]::Round([math]::Sqrt($squaredNorm), 6)
        })
    }

    $artifact = [ordered]@{
        recorded_at_utc = [DateTime]::UtcNow.ToString('o')
        ollama_version = $version.version
        base_url = $BaseUrl
        cold_start_requested = $ColdStart.IsPresent
        models = @($tags.models | Where-Object { $_.name -in @($ChatModel, $EmbeddingModel) } |
            Select-Object name, digest, size, details)
        results = @($results.ToArray())
        scope = 'Synthetic Ollama readiness checks only; not backend, database, accuracy, throughput, or p95 validation.'
    }
    $outputPath = if ([IO.Path]::IsPathRooted($OutputFile)) {
        [IO.Path]::GetFullPath($OutputFile)
    } else {
        [IO.Path]::GetFullPath((Join-Path $projectDirectory $OutputFile))
    }
    $outputDirectory = Split-Path -Parent $outputPath
    $null = New-Item -ItemType Directory -Force -Path $outputDirectory
    [IO.File]::WriteAllText($outputPath, ($artifact | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
    $results | Format-Table feature, phase, model, dimensions, wall_ms, load_ms -AutoSize
    Write-Host "Ollama chat and embedding readiness passed. Evidence: $outputPath"
} finally {
    Pop-Location
}
