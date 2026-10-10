param([string]$PgBinPath = 'C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target\local-pg'))
if (-not $testRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Invalid test cluster path'
}
if (Test-Path -LiteralPath (Join-Path $testRoot 'PG_VERSION')) {
    & (Join-Path $PgBinPath 'pg_ctl.exe') '-D' $testRoot '-m' 'fast' '-w' 'stop'
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the isolated test cluster' }
}
