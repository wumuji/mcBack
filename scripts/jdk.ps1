# Locates a JDK 21+ installation and exposes $resolvedJdk with tool paths.
# This script is intentionally ASCII-only so it works in Windows PowerShell 5.1
# (which reads .ps1 files using the ANSI code page when no BOM is present).

function Get-JdkMajorVersion {
    param([string]$JavacPath)
    try {
        $raw = & $JavacPath -version 2>&1 | Out-String
        if ($raw -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    } catch {
        return 0
    }
    return 0
}

function Resolve-Jdk {
    $candidates = New-Object System.Collections.Generic.List[string]

    # 1) javac.exe already on PATH
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates.Add($cmd.Source) }

    # 2) JAVA_HOME
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\javac.exe')) }

    # 3) well known JDK install roots
    $roots = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\BellSoft',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Zulu',
        'C:\Program Files\Amazon Corretto',
        'C:\Program Files\AdoptOpenJDK',
        'C:\Program Files\Semeru',
        'C:\Program Files\Tencent'
    )
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $candidates.Add((Join-Path $_.FullName 'bin\javac.exe'))
        }
    }

    # 4) generic scan of "Program Files" for jdk-like folders (one level deep)
    foreach ($root in @('C:\Program Files', 'C:\Program Files (x86)', 'C:\')) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)jdk|jre|java|temurin|corretto|zulu|graal' } |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\javac.exe')) }
    }

    foreach ($javac in $candidates) {
        if ([string]::IsNullOrWhiteSpace($javac)) { continue }
        if (-not (Test-Path -LiteralPath $javac)) { continue }
        $bin = Split-Path -Parent $javac
        # the Oracle "javapath" shim only contains java/javac/javaw; skip it, we need jar/jpackage too
        if (-not (Test-Path -LiteralPath (Join-Path $bin 'jar.exe'))) { continue }
        $major = Get-JdkMajorVersion -JavacPath $javac
        if ($major -ge 21) {
            $jdkHome = Split-Path -Parent $bin
            return [PSCustomObject]@{
                Home    = $jdkHome
                Bin     = $bin
                Javac   = $javac
                Java    = (Join-Path $bin 'java.exe')
                Javaw   = (Join-Path $bin 'javaw.exe')
                Jar     = (Join-Path $bin 'jar.exe')
                Jlink   = (Join-Path $bin 'jlink.exe')
                Jpackage = (Join-Path $bin 'jpackage.exe')
                Version = $major
            }
        }
    }

    throw "No JDK 21 or newer found. Install a JDK 21 (e.g. Temurin/Oracle/Zulu) or set JAVA_HOME, then retry. Note: JAVA_HOME currently points to '$env:JAVA_HOME'."
}
