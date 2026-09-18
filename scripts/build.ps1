# Compiles the MCBackup main sources and packages them into out/mcbackup.jar.
# Usage: powershell -File scripts\build.ps1 [-Clean]

param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdk = Resolve-Jdk
$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'src\main\java'
$outDir = Join-Path $root 'out'
$classesDir = Join-Path $outDir 'classes'
$jarFile = Join-Path $outDir 'mcbackup.jar'

if ($Clean) {
    if (Test-Path -LiteralPath $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$sources = @(Get-ChildItem -LiteralPath $srcDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw "No Java sources found under $srcDir" }

Write-Host ("[build] JDK {0} ({1})" -f $jdk.Version, $jdk.Home)
Write-Host ("[build] compiling {0} source files" -f $sources.Count)
$sw = [System.Diagnostics.Stopwatch]::StartNew()

# -J-Duser.language=en keeps javac diagnostics in English so they stay readable
# regardless of the console code page.
# Swing 组件天然是 Serializable 且构造期必然发生 this 逃逸,这两类告警对本项目没有价值,
# 因此关闭 serial / this-escape,保留其它所有告警。
& $jdk.Javac '-J-Duser.language=en' '-J-Duser.country=US' '-encoding' 'UTF-8' '-Xlint:all,-serial,-this-escape' '-d' $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

& $jdk.Jar '--create' '--file' $jarFile '--main-class' 'com.mcbackup.App' '-C' $classesDir '.'
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

$sw.Stop()
$sizeKb = [math]::Round((Get-Item -LiteralPath $jarFile).Length / 1KB, 1)
Write-Host ("[build] OK in {0}s -> {1} ({2} KB)" -f [math]::Round($sw.Elapsed.TotalSeconds, 2), $jarFile, $sizeKb)
