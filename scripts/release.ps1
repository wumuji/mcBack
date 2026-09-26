# One-command release: checks the version, builds, tests, packages, smoke tests and
# assembles release\v<version>\ with the artifacts and a SHA256SUMS file.
# Usage: powershell -File scripts\release.ps1 -Version 1.0.0 [-SkipTests] [-Tag]

param(
    [string]$Version = '1.0.0',
    [switch]$SkipTests,
    [switch]$Tag
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')

$root = Split-Path -Parent $PSScriptRoot
$releaseDir = Join-Path $root 'release'
$targetDir = Join-Path $releaseDir ("v{0}" -f $Version)

function Get-DeclaredVersion([string]$path, [string]$pattern) {
    $text = Get-Content -LiteralPath $path -Raw -Encoding utf8
    if ($text -match $pattern) { return $Matches[1] }
    return $null
}

Write-Host ('[release] target version: {0}' -f $Version)
$appVersion = Get-DeclaredVersion (Join-Path $root 'src\main\java\com\mcback\App.java') 'VERSION\s*=\s*"([^"]+)"'
$packageVersion = Get-DeclaredVersion (Join-Path $root 'scripts\package.ps1') "\`$appVersion\s*=\s*'([^']+)'"
if ($appVersion -ne $Version) {
    throw "App.java declares version '$appVersion' but release version is '$Version' - bump App.VERSION first"
}
if ($packageVersion -ne $Version) {
    throw "package.ps1 declares version '$packageVersion' but release version is '$Version' - bump it first"
}
Write-Host '[release] version numbers in source are consistent'

if ($SkipTests) {
    & (Join-Path $PSScriptRoot 'build.ps1') -Clean
} else {
    & (Join-Path $PSScriptRoot 'build.ps1') -Clean
    & (Join-Path $PSScriptRoot 'test.ps1')
    if ($LASTEXITCODE -ne 0) { throw "tests failed with exit code $LASTEXITCODE" }
}

& (Join-Path $PSScriptRoot 'package.ps1')

Write-Host '[release] release smoke test on the packaged build'
& (Join-Path $PSScriptRoot 'release-smoke.ps1') -SkipPackage -Cycles 3
if ($LASTEXITCODE -ne 0) { throw "release smoke test failed with exit code $LASTEXITCODE" }

# 组装 release\v<version>
if (Test-Path -LiteralPath $targetDir) { Remove-Item -LiteralPath $targetDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$portableZip = Join-Path $releaseDir 'mcBack-portable.zip'
$installer = Join-Path $releaseDir ("mcBack-{0}-Setup.exe" -f $Version)

Copy-Item -LiteralPath $portableZip -Destination (Join-Path $targetDir ("mcBack-{0}-portable.zip" -f $Version))
if (Test-Path -LiteralPath $installer) {
    Copy-Item -LiteralPath $installer -Destination $targetDir
} else {
    Write-Host '[release] WARNING: installer not found, portable zip only'
}

$notes = Join-Path $root ("docs\RELEASE_NOTES-v{0}.md" -f $Version)
if (Test-Path -LiteralPath $notes) {
    Copy-Item -LiteralPath $notes -Destination (Join-Path $targetDir 'RELEASE_NOTES.md')
}
Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination (Join-Path $targetDir 'LICENSE')

# 校验和:用户拿到文件后可以自己核对
$hashLines = @()
Get-ChildItem -LiteralPath $targetDir -File | Where-Object { $_.Extension -ne '.txt' } | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    $hashLines += ('{0}  {1}' -f $hash, $_.Name)
}
$hashLines | Set-Content -LiteralPath (Join-Path $targetDir 'SHA256SUMS.txt') -Encoding ascii

Write-Host '[release] artifacts:'
Get-ChildItem -LiteralPath $targetDir | ForEach-Object {
    if ($_.PSIsContainer) { Write-Host ('  {0}/' -f $_.Name) }
    else { Write-Host ('  {0}  ({1} MB)' -f $_.Name, [math]::Round($_.Length / 1MB, 1)) }
}

if ($Tag) {
    $tag = 'v{0}' -f $Version
    $existing = git -C $root tag --list $tag
    if ($existing) {
        Write-Host ('[release] tag {0} already exists, skipped' -f $tag)
    } else {
        git -C $root tag -a $tag -m ("mcBack {0}" -f $Version)
        Write-Host ('[release] git tag {0} created' -f $tag)
    }
}

Write-Host ('[release] done -> {0}' -f $targetDir)
