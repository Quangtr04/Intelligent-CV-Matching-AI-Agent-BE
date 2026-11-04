param(
    [string]$ChatModel = 'gemini-2.5-flash',
    [string]$EmbeddingModel = 'gemini-embedding-001'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    Add-Type -AssemblyName System.Net.Http
    $settings = @{}
    foreach ($entry in Get-Content -LiteralPath .env -Encoding UTF8) {
        if ($entry -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim() }
    }
    $apiKey = $env:GEMINI_API_KEY
    if (!$apiKey) { $apiKey = $settings['GEMINI_API_KEY'] }
    if (!$apiKey) { $apiKey = $settings['OPENAI_API_KEY'] }
    if (!$apiKey) { throw 'No Gemini API key configured.' }
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromMinutes(2)
    function Send-ProbeRequest([string]$method, [string]$url, $body, [bool]$compatible) {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method), $url)
        if ($compatible) {
            $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
        } else {
            $request.Headers.TryAddWithoutValidation('x-goog-api-key', $apiKey) | Out-Null
        }
        if ($null -ne $body) {
            $request.Content = [Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 30 -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        try {
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            try {
                $json = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                if (!$response.IsSuccessStatusCode) {
                    return @{ http_status = [int]$response.StatusCode; api_status = $json.error.status; failed = $true }
                }
                return @{ http_status = [int]$response.StatusCode; data = $json; failed = $false }
            } finally { $response.Dispose() }
        } finally { $request.Dispose() }
    }
    $result = [ordered]@{}
    $models = Send-ProbeRequest 'GET' 'https://generativelanguage.googleapis.com/v1beta/models' $null $false
    $result.models_http_status = $models.http_status
    if (!$models.failed) {
        $result.available_models = @($models.data.models | Where-Object { $_.name -match 'gemini-.*(flash|embedding)' } | ForEach-Object { @{ name=$_.name; methods=$_.supportedGenerationMethods } })
    } else { $result.models_api_status = $models.api_status }
    $embedding = Send-ProbeRequest 'POST' 'https://generativelanguage.googleapis.com/v1beta/openai/embeddings' @{model=$EmbeddingModel; input='Synthetic token measurement probe.'; dimensions=1024; encoding_format='float'} $true
    $result.embedding_http_status = $embedding.http_status
    if (!$embedding.failed) {
        $vector = @($embedding.data.data[0].embedding)
        $squareSum = 0.0
        foreach ($value in $vector) { $squareSum += [double]$value * [double]$value }
        $result.embedding_dimensions = $vector.Count
        $result.embedding_l2_norm = [Math]::Sqrt($squareSum)
        $result.embedding_usage = $embedding.data.usage
    } else { $result.embedding_api_status = $embedding.api_status }
    $chat = Send-ProbeRequest 'POST' 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions' @{model=$ChatModel; messages=@(@{role='user';content='Return a JSON object with ok set to true.'}); max_tokens=64; reasoning_effort='none'; response_format=@{type='json_object'}} $true
    $result.chat_http_status = $chat.http_status
    if (!$chat.failed) {
        $result.chat_model = $chat.data.model
        $result.chat_usage = $chat.data.usage
        $result.chat_finish_reason = $chat.data.choices[0].finish_reason
    } else { $result.chat_api_status = $chat.api_status }
    $directory = Join-Path $projectDirectory 'target/ai-token-measurements-gemini'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $text = $result | ConvertTo-Json -Depth 10
    [IO.File]::WriteAllText((Join-Path $directory 'probe.json'), $text, [Text.UTF8Encoding]::new($false))
    Write-Output $text
} finally {
    if ($client) { $client.Dispose() }
    Pop-Location
}
