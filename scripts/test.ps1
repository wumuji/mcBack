# Compiles and runs the JUnit 5 test suite.
# The JUnit console jar is downloaded once into .tools/ (test-only dependency).
# Usage: powershell -File scripts\test.ps1 [-Force]

param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdk = Resolve-Jdk
$root = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $root '.tools'
$junitVersion = '1.10.2'
$junitJar = Join-Path $toolsDir ("junit-platform-console-standalone-{0}.jar" -f $junitVersion)
$junitUrl = "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$junitVersion/junit-platform-console-standalone-$junitVersion.jar"

New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

if ($Force -or -not (Test-Path -LiteralPath $junitJar)) {
    Write-Host "[test] downloading JUnit console jar (one time)"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $junitUrl -OutFile $junitJar -UseBasicParsing
    } catch {
        if (Test-Path -LiteralPath $junitJar) { Remove-Item -LiteralPath $junitJar -Force }
        throw "Failed to download $junitUrl : $($_.Exception.Message)`nDownload it manually to $junitJar and re-run this script."
    }
}

# main classes must exist for the test classpath
& (Join-Path $PSScriptRoot 'build.ps1')

$mainClasses = Join-Path $root 'out\classes'
$testSrcDir = Join-Path $root 'src\test\java'
$testClasses = Join-Path $root 'out\test-classes'
if (Test-Path -LiteralPath $testClasses) { Remove-Item -LiteralPath $testClasses -Recurse -Force }
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null

$testSources = @(Get-ChildItem -LiteralPath $testSrcDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
if ($testSources.Count -eq 0) { throw "No test sources found under $testSrcDir" }

Write-Host ("[test] compiling {0} test files" -f $testSources.Count)
& $jdk.Javac '-J-Duser.language=en' '-J-Duser.country=US' '-encoding' 'UTF-8' '-d' $testClasses '-cp' "$mainClasses;$junitJar" $testSources
if ($LASTEXITCODE -ne 0) { throw "test compilation failed with exit code $LASTEXITCODE" }

$reportDir = Join-Path $root 'build\test-reports'
Write-Host "[test] running JUnit"
& $jdk.Java '-Dfile.encoding=UTF-8' '-jar' $junitJar '--class-path' "$mainClasses;$testClasses" '--scan-class-path' $testClasses '--details=tree' '--disable-ansi-colors' "--reports-dir=$reportDir"
$code = $LASTEXITCODE
Write-Host ("[test] exit code {0}, reports in {1}" -f $code, $reportDir)
exit $code
