param([string]$JavaHome='C:\Program Files\Java\jdk-17', [string]$FixtureDirectory='', [string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\normalized-blendshape-input-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@((Join-Path $root 'app\src\main\java\com\mirror\bench\NormalizedBlendshapeInput.java'),(Join-Path $PSScriptRoot 'NormalizedBlendshapeInputTest.java'))
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Normalized blendshape input compilation failed.'}
if([bool]$FixtureDirectory -ne [bool]$OutputDirectory){throw 'Fixture and new output directories must be supplied together.'}
$arguments=@('-cp',$classes,'com.mirror.bench.NormalizedBlendshapeInputTest')
if($FixtureDirectory){$arguments+=@($FixtureDirectory,$OutputDirectory)}
& (Join-Path $JavaHome 'bin\java.exe') @arguments
if($LASTEXITCODE -ne 0){throw 'Normalized blendshape input tests failed.'}
