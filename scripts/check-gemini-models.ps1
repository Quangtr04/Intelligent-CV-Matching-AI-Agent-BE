$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$projectDirectory = Split-Path -Parent $PSScriptRoot
$settings = @{}
foreach ($entry in Get-Content -LiteralPath (Join-Path $projectDirectory '.env') -Encoding UTF8) {
    if ($entry -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim() }
}
$apiKey = if ($env:GEMINI_API_KEY) { $env:GEMINI_API_KEY } else { $settings['GEMINI_API_KEY'] }
if (!$apiKey) { throw 'GEMINI_API_KEY is missing.' }
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(1)
$results = @()
try {
    foreach ($model in @('gemini-2.5-flash-lite','gemini-3.1-flash-lite','gemini-3.8-flash')) {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions')
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
        $body = @{model=$model; messages=@(@{role='user';content='Return JSON: {"ok":true}'}); max_tokens=64; response_format=@{type='json_object'}}
        if ($model -like 'gemini-2.5-*') { $body.reasoning_effort = 'none' } else { $body.reasoning_effort = 'minimal' }
        $request.Content = [Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json')
        try {
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            try {
                $json = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                $row = @{ model=$model; http_status=[int]$response.StatusCode }
                if ($response.IsSuccessStatusCode) {
                    $row.usage = $json.usage
                    $row.finish_reason = $json.choices[0].finish_reason
                } else {
                    $row.api_status = $json.error.status
                    $message = [string]$json.error.message
                    $row.message = $message.Replace($apiKey, '[REDACTED]').Substring(0,[Math]::Min(300,$message.Length))
                }
                $results += $row
                Write-Output ($row | ConvertTo-Json -Depth 8 -Compress)
                if ($response.IsSuccessStatusCode) { break }
            } finally { $response.Dispose() }
        } finally { $request.Dispose() }
    }
    $directory = Join-Path $projectDirectory 'target/ai-token-measurements-gemini'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $directory 'model-probes.json'), ($results | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
} finally { $client.Dispose() }
