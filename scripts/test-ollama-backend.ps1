param(
    [string]$JarPath,
    [string]$OllamaBaseUrl = 'http://127.0.0.1:11434',
    [string]$ChatModel = 'qwen2.5:7b',
    [string]$EmbeddingModel = 'qwen3-embedding:0.6b',
    [string]$PostgresImage = 'pgvector/pgvector:0.8.7-pg17-bookworm',
    [ValidateRange(30, 900)]
    [int]$StartupTimeoutSeconds = 180,
    [ValidateRange(30, 3600)]
    [int]$RequestTimeoutSeconds = 600,
    [string]$OutputFile = 'target/ollama-backend-smoke.json'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$runToken = [Guid]::NewGuid().ToString('N')
$containerName = 'edunexus-ollama-smoke-' + $runToken
$runDirectory = Join-Path $projectDirectory ('target/ollama-backend-smoke-' + $runToken)
$runtimeEnvPath = Join-Path $runDirectory 'runtime.env'
$postgresEnvPath = Join-Path $runDirectory 'postgres.env'
$stdoutPath = Join-Path $runDirectory 'backend.stdout.log'
$stderrPath = Join-Path $runDirectory 'backend.stderr.log'
$containerId = $null
$containerCreationAttempted = $false
$backendProcess = $null
$phase = 'preflight'
$failedPhase = $null
$failureType = $null
$smokePassed = $false
$backendStopped = $true
$databaseRemoved = $true
$secretFilesRemoved = $true
$snapshot = $null
$reply = $null
$detail = $null
$jarHash = $null
$ollamaVersion = $null
$modelMetadata = @()
$chatWallMilliseconds = $null
$backendPort = $null
$databasePort = $null
$databaseUser = 'edunexus_smoke'
$databaseName = 'edunexus_smoke'
$utf8 = New-Object Text.UTF8Encoding($false)
$outputPath = if ([IO.Path]::IsPathRooted($OutputFile)) {
    [IO.Path]::GetFullPath($OutputFile)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectDirectory $OutputFile))
}

function New-SmokeSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Invoke-SmokeDocker([string[]]$DockerArguments, [switch]$AllowFailure) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $captured = @(& docker @DockerArguments 2>&1)
        $nativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($nativeExitCode -ne 0 -and !$AllowFailure) {
        throw 'A Docker operation failed. No container environment or raw diagnostic output was printed.'
    }
    return @{ ExitCode = $nativeExitCode; Text = ($captured | ForEach-Object { $_.ToString() }) -join "`n" }
}

function Invoke-BackendSmokeRequest(
    [string]$Path,
    [string]$Method = 'Get',
    [hashtable]$Headers = @{},
    [hashtable]$Body
) {
    $request = @{
        Uri = ('http://127.0.0.1:' + $backendPort + $Path)
        Method = $Method
        Headers = $Headers
        TimeoutSec = $RequestTimeoutSeconds
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5 -Compress))
    }
    return Invoke-RestMethod @request
}

Push-Location -LiteralPath $projectDirectory
try {
    $null = New-Item -ItemType Directory -Path $runDirectory
    $null = Get-Command docker -CommandType Application -ErrorAction Stop
    $javaPath = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $null = Invoke-SmokeDocker @('version', '--format', '{{.Server.Version}}')

    $ollamaUri = $null
    if (![Uri]::TryCreate($OllamaBaseUrl, [UriKind]::Absolute, [ref]$ollamaUri) -or
        !$ollamaUri.IsLoopback -or $ollamaUri.Scheme -notin @('http', 'https')) {
        throw 'OllamaBaseUrl must point to a loopback HTTP endpoint.'
    }
    if ($ChatModel -match ':cloud$' -or $EmbeddingModel -match ':cloud$') {
        throw 'Only downloaded local model names are supported.'
    }
    $OllamaBaseUrl = $OllamaBaseUrl.TrimEnd('/')
    $ollamaVersion = (Invoke-RestMethod -Uri ($OllamaBaseUrl + '/api/version') -TimeoutSec 10).version
    $tags = Invoke-RestMethod -Uri ($OllamaBaseUrl + '/api/tags') -TimeoutSec 10
    foreach ($model in @($ChatModel, $EmbeddingModel)) {
        if (@($tags.models | Where-Object { $_.name -eq $model }).Count -ne 1) {
            throw 'A configured local model is missing. Prepare Ollama models before this backend smoke test.'
        }
    }
    $modelMetadata = @($tags.models | Where-Object { $_.name -in @($ChatModel, $EmbeddingModel) } |
        Select-Object name, digest, size)

    if (!$JarPath) {
        $jars = @(Get-ChildItem -LiteralPath 'target' -Filter '*.jar' -File)
        if ($jars.Count -ne 1) { throw 'Specify -JarPath when target does not contain exactly one built application JAR.' }
        $JarPath = $jars[0].FullName
    }
    $JarPath = (Resolve-Path -LiteralPath $JarPath).Path
    $jarHash = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()

    $settings = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath 'src/test/resources/test.env' -Encoding UTF8) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
    }
    $databasePassword = New-SmokeSecret
    [IO.File]::WriteAllLines($postgresEnvPath, @(
        'POSTGRES_DB=' + $databaseName
        'POSTGRES_USER=' + $databaseUser
        'POSTGRES_PASSWORD=' + $databasePassword
    ), $utf8)

    $phase = 'database_start'
    $containerCreationAttempted = $true
    $databaseRemoved = $false
    $run = Invoke-SmokeDocker @(
        'run', '--detach', '--rm', '--name', $containerName,
        '--label', ('com.edunexus.smoke.run=' + $runToken),
        '--label', 'com.edunexus.smoke.owner=ollama-backend',
        '--publish', '127.0.0.1::5432', '--env-file', $postgresEnvPath, $PostgresImage
    )
    $containerIds = @($run.Text -split "`r?`n" | Where-Object { $_ -match '^[a-f0-9]{64}$' })
    if ($containerIds.Count -ne 1) { throw 'Docker did not return the expected owned container ID.' }
    $containerId = $containerIds[0]
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $databaseReady = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $ready = Invoke-SmokeDocker @(
            'exec', $containerId, 'pg_isready', '-h', '127.0.0.1', '-U', $databaseUser, '-d', $databaseName
        ) -AllowFailure
        if ($ready.ExitCode -eq 0) { $databaseReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$databaseReady) { throw 'The disposable PostgreSQL server did not become ready.' }
    $binding = (Invoke-SmokeDocker @('port', $containerId, '5432/tcp')).Text.Trim()
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'The database does not have the expected loopback port binding.' }
    $databasePort = [int]$Matches[1]

    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $backendPort = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    $overrides = @{
        POSTGRES_DB = $databaseName
        POSTGRES_USER = $databaseUser
        POSTGRES_PASSWORD = $databasePassword
        POSTGRES_PORT = [string]$databasePort
        DATABASE_URL = ('jdbc:postgresql://127.0.0.1:' + $databasePort + '/' + $databaseName)
        APP_JWT_SECRET = (New-SmokeSecret)
        PAYMENT_WEBHOOK_SECRET = (New-SmokeSecret)
        AI_ENABLED = 'true'
        AI_PROVIDER = 'ollama'
        AI_CHAT_PROVIDER = 'ollama'
        AI_EMBEDDING_PROVIDER = 'ollama'
        AI_CHAT_MODEL = $ChatModel
        AI_EMBEDDING_MODEL = $EmbeddingModel
        AI_EMBEDDING_DIMENSIONS = '1024'
        AI_MAX_OUTPUT_TOKENS = '256'
        AI_CHAT_TEMPERATURE = '0'
        OLLAMA_BASE_URL = $OllamaBaseUrl
        OLLAMA_CONTEXT_LENGTH = '4096'
        OLLAMA_EMBEDDING_CONTEXT_LENGTH = '8192'
        OLLAMA_KEEP_ALIVE = '5m'
        GUEST_TOKEN_LIMIT = '10000'
        SERVER_PORT = [string]$backendPort
        SERVER_ADDRESS = '127.0.0.1'
        SPRING_APPLICATION_NAME = ('ollama-backend-smoke-' + $runToken)
        SPRING_PROFILES_ACTIVE = 'prod'
        SPRING_FLYWAY_ENABLED = 'true'
        SPRING_JPA_HIBERNATE_DDL_AUTO = 'none'
        MAIL_ENABLED = 'false'
        AUTH_EXPOSE_DEV_OTP = 'false'
        JOB_COLLECTION_ENABLED = 'false'
        JOB_EXPIRATION_ENABLED = 'false'
        JOB_EMBEDDING_ENABLED = 'false'
        BILLING_MAINTENANCE_ENABLED = 'false'
        DOCKER_COMPOSE_ENABLED = 'false'
        APP_OCR_ENABLED = 'false'
        UPLOAD_DIR = (Join-Path $runDirectory 'uploads').Replace('\', '/')
        DEBUG = 'false'
        TRACE = 'false'
        LOGGING_LEVEL_ROOT = 'INFO'
        LOG_WEB_LEVEL = 'INFO'
        LOG_SQL_LEVEL = 'INFO'
        LOG_SPRING_WEB_LEVEL = 'INFO'
        LOG_SPRING_JDBC_LEVEL = 'INFO'
        SPRING_HTTP_LOG_REQUEST_DETAILS = 'false'
        SPRING_MAIN_BANNER_MODE = 'off'
    }
    foreach ($key in $overrides.Keys) { $settings[$key] = $overrides[$key] }
    [IO.File]::WriteAllLines($runtimeEnvPath, @($settings.Keys | ForEach-Object { $_ + '=' + $settings[$_] }), $utf8)

    $phase = 'backend_start'
    $originalEnvironment = @{}
    $childKeys = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($key in $settings.Keys) { $null = $childKeys.Add($key) }
    foreach ($key in [Environment]::GetEnvironmentVariables('Process').Keys) {
        if ($key -match '^(SPRING_|AI_|OLLAMA_|OPENAI_|APP_ENV_FILE$|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|JDK_JAVA_OPTIONS$)') {
            $null = $childKeys.Add([string]$key)
        }
    }
    $null = $childKeys.Add('APP_ENV_FILE')
    try {
        foreach ($key in $childKeys) {
            $originalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $null, 'Process')
        }
        foreach ($key in $settings.Keys) { [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process') }
        [Environment]::SetEnvironmentVariable('APP_ENV_FILE', $runtimeEnvPath.Replace('\', '/'), 'Process')
        $backendProcess = Start-Process -FilePath $javaPath -ArgumentList @('-jar', ('"' + $JarPath + '"')) `
            -WorkingDirectory $runDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $backendStopped = $false
    } finally {
        foreach ($key in $originalEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($key, $originalEnvironment[$key], 'Process')
        }
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $backendReady = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $backendProcess.Refresh()
        if ($backendProcess.HasExited) { throw 'The isolated backend exited before becoming ready.' }
        try {
            $health = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $backendPort + '/actuator/health') -TimeoutSec 2
            if ($health.status -eq 'UP') { $backendReady = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (!$backendReady) { throw 'The isolated backend did not become healthy within the startup timeout.' }

    $phase = 'guest_session'
    $session = Invoke-BackendSmokeRequest '/api/v1/guest/sessions' 'Post'
    if ([string]::IsNullOrWhiteSpace($session.sessionKey)) { throw 'Guest session creation did not return a session key.' }
    $guestHeaders = @{ 'X-Guest-Session' = $session.sessionKey }
    $phase = 'guest_conversation'
    $conversation = Invoke-BackendSmokeRequest '/api/v1/guest/assistant/conversations' 'Post' $guestHeaders
    $conversationId = [Guid]::Parse($conversation.id).ToString()
    $phase = 'live_chat'
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $reply = Invoke-BackendSmokeRequest ("/api/v1/guest/assistant/conversations/$conversationId/messages") `
        'Post' $guestHeaders @{ message = 'Give one short greeting.'; retrieve = $false }
    $watch.Stop()
    $chatWallMilliseconds = [math]::Round($watch.Elapsed.TotalMilliseconds, 3)
    if ([string]::IsNullOrWhiteSpace($reply.message_text) -or $reply.model_used -ne $ChatModel -or
        $reply.sender_type -ne 'ASSISTANT' -or $reply.retrieval_used -ne $false) {
        throw 'The backend did not return the expected live local-model assistant reply.'
    }

    $phase = 'conversation_readback'
    $detail = Invoke-BackendSmokeRequest ("/api/v1/guest/assistant/conversations/$conversationId") 'Get' $guestHeaders
    $messages = @($detail.messages)
    if ($messages.Count -ne 2 -or @($messages | Where-Object { $_.sender_type -eq 'USER' }).Count -ne 1 -or
        @($messages | Where-Object { $_.sender_type -eq 'ASSISTANT' -and $_.id -eq $reply.id }).Count -ne 1) {
        throw 'GET conversation did not return exactly the persisted user and assistant messages.'
    }

    $phase = 'postgres_persistence'
    $sql = @'
SELECT json_build_object(
    'pgvector_version', (SELECT extversion FROM pg_extension WHERE extname = 'vector'),
    'flyway_version', (SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1),
    'failed_migrations', (SELECT count(*) FROM flyway_schema_history WHERE NOT success),
    'guest_sessions', (SELECT count(*) FROM guest_sessions),
    'conversations', (SELECT count(*) FROM career_assistant_conversations),
    'messages', (SELECT count(*) FROM career_assistant_messages),
    'user_messages', (SELECT count(*) FROM career_assistant_messages WHERE sender_type = 'USER'),
    'assistant_messages', (SELECT count(*) FROM career_assistant_messages WHERE sender_type = 'ASSISTANT' AND length(trim(message_text)) > 0),
    'retrieval_messages', (SELECT count(*) FROM career_assistant_messages WHERE retrieval_used),
    'reply_model', (SELECT max(model_used) FROM career_assistant_messages WHERE sender_type = 'ASSISTANT'),
    'usage_rows', (SELECT count(*) FROM ai_usage_logs WHERE feature_code = 'CAREER_CHAT' AND success),
    'failed_usage_rows', (SELECT count(*) FROM ai_usage_logs WHERE NOT success),
    'provider', (SELECT max(provider) FROM ai_usage_logs WHERE feature_code = 'CAREER_CHAT' AND success),
    'model', (SELECT max(model_name) FROM ai_usage_logs WHERE feature_code = 'CAREER_CHAT' AND success),
    'input_tokens', (SELECT coalesce(sum(input_tokens), 0) FROM ai_usage_logs WHERE feature_code = 'CAREER_CHAT' AND success),
    'output_tokens', (SELECT coalesce(sum(output_tokens), 0) FROM ai_usage_logs WHERE feature_code = 'CAREER_CHAT' AND success),
    'total_tokens', (SELECT coalesce(sum(total_tokens), 0) FROM ai_usage_logs WHERE feature_code = 'CAREER_CHAT' AND success),
    'guest_tokens_used', (SELECT coalesce(sum(token_used), 0) FROM guest_sessions),
    'trial_events', (SELECT count(*) FROM trial_usage_events),
    'completed_trial_events', (SELECT count(*) FROM trial_usage_events WHERE feature_code = 'CAREER_CHAT' AND status = 'COMPLETED')
)::text;
'@
    $query = Invoke-SmokeDocker @('exec', $containerId, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
        '-U', $databaseUser, '-d', $databaseName, '-c', $sql)
    $snapshot = $query.Text.Trim() | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($snapshot.pgvector_version) -or [string]::IsNullOrWhiteSpace($snapshot.flyway_version) -or
        $snapshot.failed_migrations -ne 0 -or $snapshot.guest_sessions -ne 1 -or $snapshot.conversations -ne 1 -or
        $snapshot.messages -ne 2 -or $snapshot.user_messages -ne 1 -or $snapshot.assistant_messages -ne 1 -or
        $snapshot.retrieval_messages -ne 0 -or $snapshot.reply_model -ne $ChatModel -or $snapshot.usage_rows -ne 1 -or
        $snapshot.failed_usage_rows -ne 0 -or $snapshot.provider -ne 'ollama' -or $snapshot.model -ne $ChatModel -or
        $snapshot.input_tokens -le 0 -or $snapshot.output_tokens -le 0 -or
        $snapshot.total_tokens -ne ($snapshot.input_tokens + $snapshot.output_tokens) -or
        $snapshot.total_tokens -ne $snapshot.guest_tokens_used -or
        $snapshot.input_tokens -ne $reply.input_tokens -or $snapshot.output_tokens -ne $reply.output_tokens -or
        $snapshot.trial_events -ne 1 -or $snapshot.completed_trial_events -ne 1) {
        throw 'The fresh PostgreSQL database did not match the expected persisted chat, model, provider, and token usage.'
    }
    $smokePassed = $true
} catch {
    $failedPhase = $phase
    $failureType = $_.Exception.GetType().FullName
} finally {
    if ($null -ne $backendProcess) {
        try {
            $backendProcess.Refresh()
            if (!$backendProcess.HasExited) { $backendProcess.Kill(); $null = $backendProcess.WaitForExit(10000) }
            $backendStopped = $backendProcess.HasExited
        } catch { $backendStopped = $false }
    }
    if ($containerCreationAttempted) {
        try {
            $containerTarget = if ($containerId) { $containerId } else { $containerName }
            $ownership = Invoke-SmokeDocker @('inspect', '--format', '{{json .Config.Labels}}', $containerTarget) -AllowFailure
            if ($ownership.ExitCode -ne 0) {
                $remaining = Invoke-SmokeDocker @('ps', '--all', '--quiet', '--filter', ('label=com.edunexus.smoke.run=' + $runToken)) -AllowFailure
                $databaseRemoved = $remaining.ExitCode -eq 0 -and [string]::IsNullOrWhiteSpace($remaining.Text)
            } else {
                $labels = $ownership.Text.Trim() | ConvertFrom-Json
                if ($labels.'com.edunexus.smoke.run' -eq $runToken) {
                    $removed = Invoke-SmokeDocker @('rm', '--force', '--volumes', $containerTarget) -AllowFailure
                    $databaseRemoved = $removed.ExitCode -eq 0
                }
            }
        } catch { $databaseRemoved = $false }
    }
    foreach ($secretPath in @($runtimeEnvPath, $postgresEnvPath)) {
        try {
            if (Test-Path -LiteralPath $secretPath) { Remove-Item -LiteralPath $secretPath -Force }
            if (Test-Path -LiteralPath $secretPath) { $secretFilesRemoved = $false }
        } catch { $secretFilesRemoved = $false }
    }
    $passed = $smokePassed -and $backendStopped -and $databaseRemoved -and $secretFilesRemoved
    if ($smokePassed -and !$passed) { $failedPhase = 'cleanup' }
    $artifact = [ordered]@{
        recorded_at_utc = [DateTime]::UtcNow.ToString('o')
        passed = $passed
        failed_phase = $failedPhase
        failure_type = $failureType
        jar_name = [IO.Path]::GetFileName($JarPath)
        jar_sha256 = $jarHash
        ollama_version = $ollamaVersion
        local_models = $modelMetadata
        expected_provider = 'ollama'
        expected_chat_model = $ChatModel
        chat_wall_ms = $chatWallMilliseconds
        reply_nonempty = ($null -ne $reply -and ![string]::IsNullOrWhiteSpace($reply.message_text))
        readback_message_count = $(if ($null -ne $detail) { @($detail.messages).Count } else { 0 })
        database = $snapshot
        owned_container_name = $containerName
        cleanup = @{ backend_stopped = $backendStopped; database_removed = $databaseRemoved; temporary_secret_files_removed = $secretFilesRemoved }
        scope = 'Live built backend JAR -> local Ollama -> guest chat -> disposable PostgreSQL/pgvector. No mocks. Chat persistence/token accounting smoke only; no embedding, retrieval, quality, throughput, or p95 benchmark.'
    }
    try {
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath)
        [IO.File]::WriteAllText($outputPath, ($artifact | ConvertTo-Json -Depth 8), $utf8)
    } finally {
        Pop-Location
    }
}

if (!$passed) {
    throw "Backend Ollama smoke failed during '$failedPhase'. Evidence: $outputPath. Isolated logs remain in $runDirectory; no secret values were printed."
}
Write-Host "Live backend Ollama chat and PostgreSQL persistence smoke passed. Evidence: $outputPath"
