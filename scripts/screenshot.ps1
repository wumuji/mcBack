# Launches the app in screenshot self-check mode: renders pages to PNG files and exits.
# Usage: powershell -File scripts\screenshot.ps1 [-OutDir build\shots] [-ExtraRoot E:\.minecraft]

param(
    [string]$OutDir = 'build\shots',
    [string[]]$ExtraRoot = @(),
    [string]$BackupDir = 'build\smoke-backups'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdk = Resolve-Jdk
$root = Split-Path -Parent $PSScriptRoot
$classesDir = Join-Path $root 'out\classes'

& (Join-Path $PSScriptRoot 'build.ps1')

if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $root $OutDir }

$BackupDirPath = $BackupDir
if (-not [System.IO.Path]::IsPathRooted($BackupDirPath)) { $BackupDirPath = Join-Path $root $BackupDirPath }

$javaArgs = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-cp', $classesDir, 'com.mcback.App', '--screenshot', $OutDir, '--backup-dir', $BackupDirPath)
foreach ($r in $ExtraRoot) { $javaArgs += @('--root', $r) }

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

& $jdk.Java $javaArgs
$code = $LASTEXITCODE
if ($code -ne 0) { throw "screenshot run failed with exit code $code" }
Write-Host "[screenshot] output: $OutDir"
Get-ChildItem -LiteralPath $OutDir -Filter *.png | Select-Object Name, Length | Format-Table -AutoSize
