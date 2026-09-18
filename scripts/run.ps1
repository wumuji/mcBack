# Builds (if needed) and launches the desktop app with javaw.
# Usage: powershell -File scripts\run.ps1 [-NoBuild] [-AppArgs "--root","E:\\.minecraft"]

param(
    [switch]$NoBuild,
    [string[]]$AppArgs = @()
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdk = Resolve-Jdk
$root = Split-Path -Parent $PSScriptRoot
$classesDir = Join-Path $root 'out\classes'

if (-not $NoBuild -or -not (Test-Path -LiteralPath $classesDir)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$javaArgs = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8', '-cp', $classesDir, 'com.mcbackup.App')
if ($AppArgs.Count -gt 0) { $javaArgs += $AppArgs }

Write-Host "[run] launching $($jdk.Javaw)"
Start-Process -FilePath $jdk.Javaw -ArgumentList $javaArgs -WorkingDirectory $root | Out-Null
$logDir = Join-Path $env:APPDATA 'MCBackup\logs'
Write-Host "[run] log directory: $logDir"
