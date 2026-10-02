param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\avatar-multiview-fixture-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('AvatarPoseFixtures','AvatarPixelComparison','BlendshapeSchema') | ForEach-Object {Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=Join-Path $root 'tests\AvatarMultiviewFixturesTest.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Multiview fixture compilation failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.AvatarMultiviewFixturesTest
if($LASTEXITCODE -ne 0){throw 'Multiview fixture tests failed.'}
