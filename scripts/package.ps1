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
$releaseDir = Join-Path $root 'release'
$releaseApp = Join-Path $releaseDir 'MCBackup'
$classesDir = Join-Path $root 'out\classes'
$appVersion = '0.3.0'

& (Join-Path $PSScriptRoot 'build.ps1')

# 打包前先确认没有正在运行的实例:jpackage 的两段式启动器会留下子进程,
# 被占用的 exe/jar 删不掉,会做出「残缺的应用目录」,双击时报 Failed to launch JVM。
$running = @(Get-Process -Name 'MCBackup' -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Write-Host ('[package] stopping {0} running MCBackup process(es) to avoid locked files' -f $running.Count)
    $running | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host '[package] exporting icons'
& $jdk.Java '-Dfile.encoding=UTF-8' '-cp' $classesDir 'com.mcbackup.App' '--export-icons' $iconsDir
if ($LASTEXITCODE -ne 0) { throw "icon export failed with exit code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath (Join-Path $iconsDir 'icon.ico'))) { throw "icon.ico was not produced" }

# 1) 用 jlink 裁一个只含所需模块的运行时(java.desktop 覆盖 Swing/AWT/ImageIO,
#    jdk.charsets 用于读取 GBK 编码的启动器配置,
#    jdk.accessibility 用于兼容启用了讲述人/放大镜的机器:缺了它 AWT 初始化会直接 AWTError 崩溃)
Write-Host '[package] jlink: building trimmed runtime'
if (Test-Path -LiteralPath $runtimeDir) { Remove-Item -LiteralPath $runtimeDir -Recurse -Force }
& $jdk.Jlink `
    '--add-modules' 'java.base,java.desktop,java.logging,jdk.charsets,jdk.unsupported,jdk.accessibility' `
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

# 3) 发布到 release 目录:这个目录不会被 build/run/test 脚本动到,
#    用户双击的就是这里,避免在打包过程中点到残缺目录。
Write-Host '[package] publishing to release\MCBackup'
if (Test-Path -LiteralPath $releaseApp) { Remove-Item -LiteralPath $releaseApp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
Copy-Item -LiteralPath $appDir -Destination $releaseApp -Recurse -Force

$directCmd = Join-Path $releaseApp '直接用命令行启动.cmd'
$directCmdContent = @'
@echo off
rem 备用启动方式:跳过 jpackage 启动器,直接用自带的运行时启动。
rem 如果双击 MCBackup.exe 报 "Failed to launch JVM",用这个文件。
cd /d "%~dp0"
start "" "%~dp0runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -cp "%~dp0app\mcbackup.jar" com.mcbackup.App %*
'@
Set-Content -LiteralPath $directCmd -Value $directCmdContent -Encoding OEM

$diagCmd = Join-Path $releaseApp '出错时运行我-诊断.cmd'
$diagCmdContent = @'
@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ==== MC Backup 诊断 ====
echo 目录: %~dp0
echo.
if not exist "%~dp0runtime\bin\java.exe" echo [X] 缺少 runtime\bin\java.exe,应用目录不完整(请重新解压/重新打包)
if not exist "%~dp0app\mcbackup.jar" echo [X] 缺少 app\mcbackup.jar,应用目录不完整(请重新解压/重新打包)
if not exist "%~dp0runtime\bin\server\jvm.dll" echo [X] 缺少 runtime\bin\server\jvm.dll,运行时被删掉了一部分
echo.
echo ==== 运行时版本 ====
"%~dp0runtime\bin\java.exe" -version
echo.
echo ==== 启动程序(日志会同时打印在下面)====
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Dmcbackup.console=true -cp "%~dp0app\mcbackup.jar" com.mcbackup.App --console-log
echo.
echo ==== 退出码: %ERRORLEVEL% ====
pause
'@
Set-Content -LiteralPath $diagCmd -Value $diagCmdContent -Encoding OEM

$zipPath = Join-Path $releaseDir 'MCBackup-portable.zip'
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $releaseApp '*') -DestinationPath $zipPath -CompressionLevel Optimal

Write-Host ('[package] release: {0} ({1} MB)' -f $releaseApp, (Get-DirectorySizeMb $releaseApp))
Write-Host ('[package] zip:     {0} ({1} MB)' -f $zipPath, [math]::Round((Get-Item $zipPath).Length / 1MB, 1))
Write-Host '[package] done'
