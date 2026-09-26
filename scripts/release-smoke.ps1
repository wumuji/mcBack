# Release smoke test: proves the PACKAGED build really works, end to end.
# It builds a throwaway world, then uses release\mcBack's own runtime + jar to do
# scan -> backup -> zip verify -> restore -> file-by-file compare -> temp cleanup.
# Usage: powershell -File scripts\release-smoke.ps1 [-Cycles 2] [-SkipPackage]

param(
    [int]$Cycles = 2,
    [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$releaseApp = Join-Path $root 'release\mcBack'
$runtimeJava = Join-Path $releaseApp 'runtime\bin\java.exe'
$packagedJar = Join-Path $releaseApp 'app\mcBack.jar'
$packagedExe = Join-Path $releaseApp 'mcBack.exe'

if (-not $SkipPackage) {
    Write-Host '[smoke] building the portable package first'
    & (Join-Path $PSScriptRoot 'package.ps1') | Out-Host
}

foreach ($path in @($runtimeJava, $packagedJar, $packagedExe)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "missing packaged file: $path (run scripts\package.ps1 first)"
    }
}

# 安全网:打包产物比源码旧时直接拒绝,避免用旧 jar 跑测试(旧 jar 不认识新参数会直接开界面)
$sourceJar = Join-Path $root 'out\mcBack.jar'
if (Test-Path -LiteralPath $sourceJar) {
    $packagedTime = (Get-Item -LiteralPath $packagedJar).LastWriteTime
    $sourceTime = (Get-Item -LiteralPath $sourceJar).LastWriteTime
    if ($packagedTime -lt $sourceTime) {
        throw "release\mcBack\app\mcBack.jar is older than out\mcBack.jar - run scripts\package.ps1 first (or drop -SkipPackage)"
    }
}

$work = Join-Path $root 'build\smoke'
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
$saves = Join-Path $work 'saves'
$world = Join-Path $saves '冒烟世界'
$backups = Join-Path $work 'backups'
New-Item -ItemType Directory -Force -Path (Join-Path $world 'region'), (Join-Path $world 'playerdata'),
    (Join-Path $world 'advancements'), (Join-Path $world 'data') | Out-Null

$levelBytes = New-Object byte[] 4096
for ($i = 0; $i -lt $levelBytes.Length; $i++) { $levelBytes[$i] = [byte]($i % 251) }
$gzipHeader = New-Object byte[] 2
$gzipHeader[0] = 0x1F
$gzipHeader[1] = 0x8B
[IO.File]::WriteAllBytes((Join-Path $world 'level.dat'), ($gzipHeader + $levelBytes))
[IO.File]::WriteAllBytes((Join-Path $world 'region\r.0.0.mca'), (New-Object byte[] 2097152))
[IO.File]::WriteAllBytes((Join-Path $world 'region\r.0.1.mca'), (New-Object byte[] 1048576))
[IO.File]::WriteAllText((Join-Path $world 'playerdata\00000000-0000-0000-0000-000000000000.dat'), 'player data')
[IO.File]::WriteAllText((Join-Path $world 'session.lock'), 'lock')
[IO.File]::WriteAllText((Join-Path $world '中文文件名测试.txt'), '中文内容')

$files = Get-ChildItem -LiteralPath $world -Recurse -File
$before = ($files | Measure-Object -Property Length -Sum).Sum
Write-Host ('[smoke] fixture world: {0} ({1} MB, {2} files)' -f $world,
    [math]::Round($before / 1MB, 1), $files.Count)

$javaArgs = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-cp', $packagedJar, 'com.mcback.App', '--self-test', $saves, '--backup-dir', $backups,
    '--self-test-cycles', "$Cycles", '--console-log')

Write-Host '[smoke] running self test on the packaged build'
Write-Host '[smoke] note: the synthetic level.dat is intentionally not real NBT,'
Write-Host '[smoke]       so "level.dat parse failed" warnings below are expected and prove'
Write-Host '[smoke]       that unreadable world info never blocks backup/restore.'
$stdoutFile = Join-Path $work 'self-test.out.txt'
$stderrFile = Join-Path $work 'self-test.err.txt'
$selfTest = Start-Process -FilePath $runtimeJava -ArgumentList $javaArgs -PassThru -NoNewWindow `
    -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
$selfTest | Wait-Process -Timeout 300 -ErrorAction SilentlyContinue
if (-not $selfTest.HasExited) {
    Write-Host '[smoke] self test timed out after 300s, killing it'
    $selfTest | Stop-Process -Force -ErrorAction SilentlyContinue
    $code = 99
} else {
    $code = $selfTest.ExitCode
}
Get-Content -LiteralPath $stdoutFile -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
Get-Content -LiteralPath $stderrFile -ErrorAction SilentlyContinue |
    Select-Object -Last 20 | ForEach-Object { Write-Host "  ! $_" }

$process = Start-Process -FilePath $packagedExe -ArgumentList @('--no-auto-scan') -PassThru
Start-Sleep -Seconds 6
$exeAlive = -not $process.HasExited
if (-not $exeAlive) {
    Write-Host '[smoke] WARNING: packaged mcBack.exe exited immediately'
} else {
    Write-Host '[smoke] packaged mcBack.exe started OK'
}
Get-Process -Name mcBack -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

$zipCount = (Get-ChildItem -LiteralPath $backups -Recurse -File -Filter '*.zip' -ErrorAction SilentlyContinue |
    Measure-Object).Count
$manifestCount = (Get-ChildItem -LiteralPath $backups -Recurse -File -Filter '*.json' -ErrorAction SilentlyContinue |
    Measure-Object).Count
$leftoverTmp = (Get-ChildItem -LiteralPath $backups -Recurse -File -Filter '*.tmp' -ErrorAction SilentlyContinue |
    Measure-Object).Count
Write-Host ('[smoke] backups: {0} zip / {1} manifest / {2} leftover tmp' -f $zipCount, $manifestCount, $leftoverTmp)

$passed = ($code -eq 0) -and $exeAlive -and ($zipCount -ge 1) -and ($manifestCount -ge 1) -and ($leftoverTmp -eq 0)

if ($passed) {
    Write-Host '[smoke] PASS'
    exit 0
}
Write-Host ('[smoke] FAIL (self-test exit={0}, exe alive={1}, zip={2}, manifest={3}, tmp={4})' -f $code,
    $exeAlive, $zipCount, $manifestCount, $leftoverTmp)
exit 1
