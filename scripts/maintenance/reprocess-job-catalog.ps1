param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $backendRoot '../..'))
$values = @{}
Get-Content -LiteralPath (Join-Path $backendRoot '.env') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $values[$matches[1]] = $matches[2].Trim().Trim('"') }
}
$prior = @{}
foreach ($name in @('JOB_REPROCESS_DB_URL','JOB_REPROCESS_DB_USER','JOB_REPROCESS_DB_PASSWORD')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location -LiteralPath $backendRoot
try {
    if (-not $env:JOB_REPROCESS_DB_URL) {
        $port = if ($values['POSTGRES_PORT']) { $values['POSTGRES_PORT'] } else { '5432' }
        $database = if ($values['POSTGRES_DB']) { $values['POSTGRES_DB'] } else { 'edunexus' }
        $env:JOB_REPROCESS_DB_URL = "jdbc:postgresql://localhost:${port}/${database}"
    }
    if (-not $env:JOB_REPROCESS_DB_USER) { $env:JOB_REPROCESS_DB_USER = $values['POSTGRES_USER'] }
    if (-not $env:JOB_REPROCESS_DB_PASSWORD) { $env:JOB_REPROCESS_DB_PASSWORD = $values['POSTGRES_PASSWORD'] }
    if (-not (Test-Path -LiteralPath 'target/job-reprocess-classpath.txt')) {
        & mvn.cmd -q dependency:build-classpath '-Dmdep.outputFile=target/job-reprocess-classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare classpath' }
    }
    if (-not (Test-Path -LiteralPath 'target/classes/skills/catalog.json')) {
        throw 'Run mvn compile before reprocessing'
    }
    foreach ($resource in @('catalog.json','imported-catalog.json')) {
        $sourceHash = (Get-FileHash -LiteralPath (Join-Path 'src/main/resources/skills' $resource) -Algorithm SHA256).Hash
        $compiledHash = (Get-FileHash -LiteralPath (Join-Path 'target/classes/skills' $resource) -Algorithm SHA256).Hash
        if ($sourceHash -ne $compiledHash) { throw 'Compiled catalog is stale; run mvn compile' }
    }
    $classpath = ('target/classes;' + [IO.File]::ReadAllText(
        (Resolve-Path 'target/job-reprocess-classpath.txt')).Trim()).Replace('\','/')
    $arguments = "-cp`n`"$classpath`"`nscripts/dev/ReprocessJobRequirements.java`n"
    if ($Apply) {
        $folder = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'outputs/backups'))
        if (-not $folder.StartsWith($workspaceRoot + [IO.Path]::DirectorySeparatorChar)) {
            throw 'Backup directory is outside the workspace'
        }
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
        $backup = (Join-Path $folder ('job-catalog-before-reprocess-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')).Replace('\','/')
        $arguments += "--apply`n`"$backup`"`n"
    } else {
        $arguments += "--preview`n"
    }
    [IO.File]::WriteAllText([IO.Path]::GetFullPath('target/catalog-reprocess-args.txt'), $arguments)
    & java '@target/catalog-reprocess-args.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Reprocessing failed; inspect the transaction error and backup' }
} finally {
    foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name, $prior[$name], 'Process') }
    Pop-Location
}
