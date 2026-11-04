param(
    [switch]$Live,
    [switch]$AllVariants,
    [switch]$CountOnly,
    [switch]$SkipBuild,
    [string]$SamplesFile = 'scripts/ai-token-samples.json',
    [string]$OutputDirectory = 'target/ai-token-measurements-gemini'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    $resolvedOutput = [IO.Path]::GetFullPath((Join-Path $projectDirectory $OutputDirectory))
    $targetRoot = [IO.Path]::GetFullPath((Join-Path $projectDirectory 'target'))
    if (!$resolvedOutput.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'OutputDirectory must be a subdirectory of the project target directory.'
    }
    New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
    $classpathFile = Join-Path $resolvedOutput 'classpath.txt'
    if (!$SkipBuild) {
        & mvn -B -DskipTests compile
        if ($LASTEXITCODE -ne 0) { throw 'Compile failed.' }
        & mvn -B dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
        if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the runtime classpath.' }
    } elseif (!(Test-Path -LiteralPath $classpathFile) -or !(Test-Path -LiteralPath 'target/classes')) {
        throw 'Run once without -SkipBuild to prepare the compiled classes and runtime classpath.'
    }
    $classpath = (Join-Path $projectDirectory 'target/classes') + [IO.Path]::PathSeparator + [IO.File]::ReadAllText($classpathFile).Trim()
    $javaArguments = @('--class-path', $classpath, (Join-Path $PSScriptRoot 'dev/MeasureAiTokens.java'), '--output', $resolvedOutput, '--samples', $SamplesFile)
    if ($Live) { $javaArguments += '--live' }
    if ($AllVariants) { $javaArguments += '--all-variants' }
    if ($CountOnly) { $javaArguments += '--count-only' }
    $argumentsFile = Join-Path $resolvedOutput 'launcher.args'
    $quotedArguments = $javaArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }
    [IO.File]::WriteAllLines($argumentsFile, $quotedArguments, [Text.UTF8Encoding]::new($false))
    & java "@$argumentsFile"
    if ($LASTEXITCODE -ne 0) { throw 'Token measurement failed; see the redacted console diagnostics.' }
} finally {
    Pop-Location
}
