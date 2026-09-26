# Builds a self-contained Windows app image (MCBackup.exe with a trimmed Java runtime).
# No WiX needed: app-image is a portable folder the user can double-click.
# Usage: powershell -File scripts\package.ps1 [-SkipRuntimeCheck]

param(
    [switch]$SkipRuntimeCheck
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdk = Resolve-Jdk
$root = Split-Path -Parent $PSScriptRoot
$buildDir = Join-Path $root 'build'
$iconsDir = Join-Path $buildDir 'icons'
$runtimeDir = Join-Path $buildDir 'runtime'
$inputDir = Join-Path $buildDir 'package-input'
$distDir = Join-Path $buildDir 'dist'
$classesDir = Join-Path $root 'out\classes'
$appVersion = '0.3.0'

& (Join-Path $PSScriptRoot 'build.ps1')

Write-Host '[package] exporting icons'
& $jdk.Java '-Dfile.encoding=UTF-8' '-cp' $classesDir 'com.mcbackup.App' '--export-icons' $iconsDir
if ($LASTEXITCODE -ne 0) { throw "icon export failed with exit code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath (Join-Path $iconsDir 'icon.ico'))) { throw "icon.ico was not produced" }

# 1) 用 jlink 裁一个只含所需模块的运行时(java.desktop 覆盖 Swing/AWT/ImageIO,
#    jdk.charsets 用于读取 GBK 编码的启动器配置)
Write-Host '[package] jlink: building trimmed runtime'
if (Test-Path -LiteralPath $runtimeDir) { Remove-Item -LiteralPath $runtimeDir -Recurse -Force }
& $jdk.Jlink `
    '--add-modules' 'java.base,java.desktop,jdk.charsets,jdk.unsupported' `
    '--strip-debug' '--no-header-files' '--no-man-pages' '--compress=zip-6' `
    '--output' $runtimeDir
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

if (-not $SkipRuntimeCheck) {
    Write-Host '[package] smoke test: launching the app on the trimmed runtime'
    $exe = Join-Path $runtimeDir 'bin\javaw.exe'
    $proc = Start-Process -FilePath $exe -ArgumentList @('-cp', $classesDir, 'com.mcbackup.App', '--no-auto-scan') -PassThru
    Start-Sleep -Seconds 5
    if ($proc.HasExited) { throw "app failed to start on trimmed runtime (exit $($proc.ExitCode))" }
    $proc | Stop-Process -Force
    Write-Host '[package] trimmed runtime OK'
}

# 2) jpackage: 生成免安装的 app-image(内置上面的 runtime)
Write-Host '[package] jpackage: building app image'
if (Test-Path -LiteralPath $inputDir) { Remove-Item -LiteralPath $inputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'out\mcbackup.jar') -Destination $inputDir -Force
if (Test-Path -LiteralPath $distDir) { Remove-Item -LiteralPath $distDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

& $jdk.Jpackage `
    '--type' 'app-image' `
    '--name' 'MCBackup' `
    '--app-version' $appVersion `
    '--input' $inputDir `
    '--main-jar' 'mcbackup.jar' `
    '--main-class' 'com.mcbackup.App' `
    '--runtime-image' $runtimeDir `
    '--icon' (Join-Path $iconsDir 'icon.ico') `
    '--java-options' '-Dfile.encoding=UTF-8' `
    '--vendor' 'MC Backup' `
    '--description' 'Minecraft Java 存档自动备份与导出工具' `
    '--dest' $distDir
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

$appDir = Join-Path $distDir 'MCBackup'
$exePath = Join-Path $appDir 'MCBackup.exe'
if (-not (Test-Path -LiteralPath $exePath)) { throw "MCBackup.exe was not produced" }

function Get-DirectorySizeMb([string]$path) {
    $sum = 0
    $stack = New-Object System.Collections.Stack
    $stack.Push($path)
    while ($stack.Count -gt 0) {
        $dir = $stack.Pop()
        try {
            foreach ($file in [IO.Directory]::EnumerateFiles($dir)) {
                try { $sum += (New-Object IO.FileInfo $file).Length } catch { }
            }
            foreach ($sub in [IO.Directory]::EnumerateDirectories($dir)) { $stack.Push($sub) }
        } catch { }
    }
    return [math]::Round($sum / 1MB, 1)
}

Write-Host ('[package] app image: {0} ({1} MB)' -f $appDir, (Get-DirectorySizeMb $appDir))
Write-Host ('[package] runtime only: {0} MB' -f (Get-DirectorySizeMb $runtimeDir))
Write-Host ('[package] executable: {0}' -f $exePath)
