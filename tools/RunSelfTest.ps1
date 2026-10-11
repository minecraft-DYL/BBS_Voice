# Runs the built BBS Voice jar inside the real isolated PCL installation
# (D:\minecraft\.minecraft\versions\BBS), so the mod can be smoke tested against the real
# BBS 2.3 / Minecraft 1.20.4 runtime.
#
#   (default)      headless-ish self test: -Dbbsvoice.selftest=1, waits for bbsvoice-selftest.txt,
#                  then kills the client. Result lands in versions/BBS/bbsvoice-selftest.txt
#   -Interactive    hand testing: launches the client WITHOUT the self test property, returns
#                  immediately and leaves the game running for you to poke at
#
# The classpath is far longer than the Windows command line limit, so every JVM
# argument is handed over through a java @argfile.
param(
    [string]$ModJar = 'D:\ku\traecode\ai-mc-mod\BBS Voice\build\libs\bbsvoice-1.0.0-1.20.4.jar',
    [int]$TimeoutSeconds = 300,
    [switch]$Interactive
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$mc  = 'D:\minecraft\.minecraft'
$ver = Join-Path $mc 'versions\BBS'
$log = Join-Path $ver 'logs\latest.log'
$report = Join-Path $ver 'bbsvoice-selftest.txt'
$argsFile = Join-Path (Split-Path $ModJar -Parent) '..\java-args.txt'
$argsFile = [System.IO.Path]::GetFullPath($argsFile)

if (-not (Test-Path $ModJar)) { throw "Mod jar not found: $ModJar (build it first)" }

# --- locate a Java 17/21 runtime (the version profile asks for 17) -------------------
$java = $null
foreach ($candidate in @(
    'C:\Program Files\Zulu\zulu-17\bin\java.exe',
    'C:\Program Files\Zulu\zulu-21\bin\java.exe'
)) {
    if (Test-Path $candidate) { $java = $candidate; break }
}
if (-not $java) {
    $java = (Get-ChildItem 'C:\Program Files' -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'jdk|jre|zulu|temurin' } | Select-Object -First 1).FullName
}
if (-not $java) { throw 'No Java runtime found' }

# --- install the mod (purely additive, delete this one file to uninstall) ----------
$modsDir = Join-Path $ver 'mods'
Copy-Item $ModJar (Join-Path $modsDir 'bbsvoice-1.0.0-1.20.4.jar') -Force
Write-Host "installed $(Split-Path $ModJar -Leaf) -> $modsDir"

# --- classpath: exactly the libraries this version declares, + BBS.jar + mods -------
# (recursing the whole libraries folder would drag in 728 jars from other Minecraft
#  versions and versions of log4j/asm that fight each other)
$librariesRoot = Join-Path $mc 'libraries'
$versionJson = Get-Content (Join-Path $ver 'BBS.json') -Raw | ConvertFrom-Json
$libJars = @()

# Some entries (fabric-loader, mixin, asm, intermediary) only carry a maven coordinate.
function ConvertTo-LibraryPath([string]$name)
{
    $extension = 'jar'
    if ($name.Contains('@')) {
        $split = $name.Split('@')
        $name = $split[0]
        $extension = $split[1]
    }
    $parts = $name.Split(':')
    if ($parts.Count -lt 3) { return $null }
    $classifier = if ($parts.Count -gt 3) { '-' + $parts[3] } else { '' }
    return (($parts[0] -replace '\.', '/') + '/' + $parts[1] + '/' + $parts[2] + '/' + $parts[1] + '-' + $parts[2] + $classifier + '.' + $extension)
}

foreach ($library in $versionJson.libraries) {
    $artifact = $null
    if ($library.downloads -and $library.downloads.artifact) { $artifact = $library.downloads.artifact.path }
    if (-not $artifact) { $artifact = ConvertTo-LibraryPath $library.name }
    if (-not $artifact) { continue }
    $libJars += (Join-Path $librariesRoot ($artifact -replace '/', '\'))
}

$missing = $libJars | Where-Object { -not (Test-Path $_) }
if ($missing) { $missing | ForEach-Object { Write-Host "MISSING library: $_" } }

# The JVM @argfile format cannot carry paths with spaces, and this installation has mods
# named like "[Xaero的小地图] xaeroworldmap-fabric-1.20.4-1.46.0.jar". Those are optional
# for the smoke test, so they are skipped (and reported) instead of breaking the launch.
$allMods = Get-ChildItem $modsDir -Filter *.jar
$mods = $allMods | Where-Object { $_.Name -match '^[\x20-\x7E]+$' -and $_.Name -notmatch ' ' }
$skipped = $allMods | Where-Object { $_.Name -notmatch '^[\x20-\x7E]+$' -or $_.Name -match ' ' }

$cp = @(
    (Join-Path $ver 'BBS.jar')
    ($libJars | Where-Object { Test-Path $_ })
    ($mods | Select-Object -ExpandProperty FullName)
) -join ';'
$cp = $cp.Replace('\', '/')

Write-Host "classpath jars: $((($cp -split ';').Count))  (skipped $($skipped.Count) jars with spaces or non-ascii names)"
Write-Host "loader on classpath: $([bool]($cp -match 'fabric-loader'))"
$skipped | ForEach-Object { Write-Host "  skipped: $($_.Name)" }

$natives = (Join-Path $ver 'BBS-natives').Replace('\', '/')

$arguments = @(
    "-Djava.library.path=$natives",
    "-Djna.tmpdir=$natives",
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$natives",
    "-Dio.netty.native.workdir=$natives",
    '-Dminecraft.launcher.brand=pcl',
    '-Dminecraft.launcher.version=2'
)

# Interactive launches are for a human, so they must NOT carry the self test property.
if (-not $Interactive) { $arguments += '-Dbbsvoice.selftest=1' }

$arguments += @(
    '-cp',
    $cp,
    '-DFabricMcEmu=',
    $versionJson.mainClass,
    '--username', 'SelfTest',
    '--version', 'BBS',
    '--gameDir', ($ver.Replace('\', '/')),
    '--assetsDir', ((Join-Path $mc 'assets').Replace('\', '/')),
    '--assetIndex', '12',
    '--uuid', '00000000000000000000000000000000',
    '--accessToken', '0',
    '--clientId', '0',
    '--xuid', '0',
    '--userType', 'msa',
    '--versionType', 'release'
)

# The JVM argument file parser is picky: no BOM, and LF line endings only.
[System.IO.File]::WriteAllText($argsFile, ($arguments -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "argfile: $argsFile ($((Get-Item $argsFile).Length) bytes, classpath $($cp.Length) chars)"

Remove-Item $log -ErrorAction SilentlyContinue
Remove-Item $report -ErrorAction SilentlyContinue

$outLog = Join-Path (Split-Path $ModJar -Parent) '..\mc-out.log'
$errLog = Join-Path (Split-Path $ModJar -Parent) '..\mc-err.log'

Write-Host "launching $java"

if ($Interactive)
{
    # Hand it to a human: detach, do not wait, do not kill.
    Start-Process -FilePath $java -ArgumentList ('@"' + $argsFile + '"') -WorkingDirectory $ver `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog

    Write-Host 'interactive client launched (no self test property) - the game is yours'
    Write-Host "log: $log"
    Write-Host "stdout/stderr: $outLog / $errLog"

    return
}

$process = Start-Process -FilePath $java -ArgumentList ('@"' + $argsFile + '"') -WorkingDirectory $ver -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5

    if (Test-Path $report) {
        Start-Sleep -Seconds 3
        break
    }

    if ($process.HasExited) { break }
}

if (-not $process.HasExited) {
    Start-Sleep -Seconds 3
    if (-not $process.HasExited) { $process.Kill(); Write-Host 'killed the test client' }
}

Write-Host '--- selftest report ---'
if (Test-Path $report) { Get-Content $report -Encoding UTF8 } else { Write-Host "(no report at $report)" }

Write-Host '--- jvm stdout/stderr tail ---'
foreach ($file in @($errLog, $outLog)) {
    Write-Host "### $file"
    if (Test-Path $file) { Get-Content $file -Encoding UTF8 | Select-Object -Last 30 } else { Write-Host '(missing)' }
}

Write-Host '--- selftest log lines (stdout of the game process) ---'
if (Test-Path $outLog) {
    Select-String -Path $outLog -Pattern 'bbsvoice-selftest|BBS Voice|Fabric Loader|bbsvoice|at com\.bbsvoice' -Encoding UTF8 |
        ForEach-Object { $_.Line } | Select-Object -First 80
}
