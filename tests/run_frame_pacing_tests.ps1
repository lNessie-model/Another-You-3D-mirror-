param([string]$JavaHome = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) { $javac = Join-Path $JavaHome 'bin\javac.exe'; $java = Join-Path $JavaHome 'bin\java.exe' }
else { $javac = (Get-Command javac -ErrorAction Stop).Source; $java = (Get-Command java -ErrorAction Stop).Source }
$classes = Join-Path $projectRoot 'app\build\frame-pacing-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @('app\src\main\java\com\mirror\bench\FramePacingStats.java','app\src\main\java\com\mirror\bench\ViewSubmissionTiming.java',
    'tests\FramePacingStatsTest.java','tests\ViewSubmissionTimingTest.java') |
    ForEach-Object { Join-Path $projectRoot $_ }
& $javac --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Frame-pacing host compilation failed.' }
& $java -cp $classes com.mirror.bench.FramePacingStatsTest
if ($LASTEXITCODE -ne 0) { throw 'Frame-pacing host tests failed.' }
& $java -cp $classes com.mirror.bench.ViewSubmissionTimingTest
if ($LASTEXITCODE -ne 0) { throw 'View submission timing tests failed.' }
Write-Host 'Synthetic accounting passed; no Android/GL/performance evidence is implied.'
