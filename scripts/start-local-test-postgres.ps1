param(
    [string]$PgBinPath = 'C:\Program Files\PostgreSQL\18\bin',
    [int]$Port = 55439
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target\local-pg'))
if (-not $testRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The isolated test cluster must remain under this project.'
}
$pgCtl = Join-Path $PgBinPath 'pg_ctl.exe'
$psql = Join-Path $PgBinPath 'psql.exe'
if (-not (Test-Path -LiteralPath $pgCtl)) { throw "PostgreSQL binaries not found at $PgBinPath" }
New-Item -ItemType Directory -Path (Split-Path -Parent $testRoot) -Force | Out-Null

if (-not (Test-Path -LiteralPath (Join-Path $testRoot 'PG_VERSION'))) {
    & (Join-Path $PgBinPath 'initdb.exe') '-D' $testRoot '--username=edunexus_test' '--auth-local=trust' '--auth-host=trust' '--no-locale' '--encoding=UTF8'
    if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
    Add-Content -LiteralPath (Join-Path $testRoot 'postgresql.conf') -Value @"
listen_addresses = '127.0.0.1'
port = $Port
max_connections = 40
"@
}

& $pgCtl '-D' $testRoot 'status' *> $null
if ($LASTEXITCODE -ne 0) {
    & (Join-Path $PgBinPath 'pg_isready.exe') '-h' '127.0.0.1' '-p' $Port *> $null
    if ($LASTEXITCODE -eq 0) { throw "Port $Port already belongs to another database; refusing to use it." }
    $logFile = Join-Path (Split-Path -Parent $testRoot) 'local-pg-server.log'
    $startArgs = @('-D', ('"' + $testRoot + '"'), '-l', ('"' + $logFile + '"'), '-w', 'start')
    $process = Start-Process -FilePath $pgCtl -ArgumentList $startArgs -WindowStyle Hidden -PassThru
    # Wait only for pg_ctl. Start-Process -Wait includes the long-lived postgres
    # process tree and therefore never returns after a successful server start.
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Test PostgreSQL did not start; inspect $logFile" }
}

$connectionArgs = @('-h', '127.0.0.1', '-p', "$Port", '-U', 'edunexus_test', '-v', 'ON_ERROR_STOP=1')
$dbExists = & $psql @connectionArgs '-d' 'postgres' '-Atc' "SELECT 1 FROM pg_database WHERE datname = 'edunexus_test'"
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the managed test cluster' }
if ($dbExists -ne '1') {
    & (Join-Path $PgBinPath 'createdb.exe') '-h' '127.0.0.1' '-p' $Port '-U' 'edunexus_test' 'edunexus_test'
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated test database' }
}

$schemaExists = & $psql @connectionArgs '-d' 'edunexus_test' '-Atc' "SELECT to_regclass('public.users') IS NOT NULL"
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the isolated test database' }
if ($schemaExists -ne 't') {
    $migrationPath = Join-Path $projectRoot 'src\main\resources\db\migration'
    $schemaParts = Get-ChildItem -LiteralPath $migrationPath -Filter 'V*__*.sql' | Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') } | ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }
    $testSql = $schemaParts -join "`n"
    # The locally installed PostgreSQL lacks pgvector. Only this generated test
    # copy uses arrays and omits vector indexes; production migrations are intact.
    $testSql = $testSql -replace 'CREATE EXTENSION IF NOT EXISTS vector;', '-- pgvector unavailable in local schema smoke test.'
    $testSql = $testSql -replace 'vector\((1024|1536)\)', 'real[]'
    $testSql = $testSql -replace '(?s)CREATE INDEX \w+ ON \w+\s+USING hnsw \([^;]+;', '-- HNSW index omitted in local schema smoke test.'
    $schemaFile = Join-Path (Split-Path -Parent $testRoot) 'local-pg-schema.sql'
    [IO.File]::WriteAllText($schemaFile, $testSql, (New-Object Text.UTF8Encoding($false)))
    & $psql @connectionArgs '-d' 'edunexus_test' '--single-transaction' '-q' '-f' $schemaFile
    if ($LASTEXITCODE -ne 0) { throw 'Schema smoke migration failed' }
}

Write-Output 'Isolated PostgreSQL test database ready (pgvector semantics are not tested).'
Write-Output "DATABASE_URL=jdbc:postgresql://127.0.0.1:$Port/edunexus_test"
Write-Output 'POSTGRES_USER=edunexus_test'
Write-Output 'POSTGRES_PASSWORD= (empty, localhost-only test cluster)'
Write-Output 'SPRING_FLYWAY_ENABLED=false (schema installed by this script)'
