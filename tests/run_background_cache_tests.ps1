param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $projectRoot 'app\build\background-cache-tests\classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(@('AvatarBackgroundCache') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"})
$sources+=Join-Path $projectRoot 'tests\AvatarBackgroundCacheTest.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Background cache controller compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.AvatarBackgroundCacheTest
if($LASTEXITCODE -ne 0){throw 'Background cache controller checks failed'}
