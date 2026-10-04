param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskClasses=Join-Path $taskRoot 'app\build\scene-view-tests'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -d $taskClasses `
    (Join-Path $taskRoot 'app\src\main\java\com\mirror\bench\SceneViewSettings.java') (Join-Path $PSScriptRoot 'SceneViewSettingsTest.java')
if($LASTEXITCODE){throw 'Scene view settings compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasses com.mirror.bench.SceneViewSettingsTest
if($LASTEXITCODE){throw 'Scene view settings checks failed'}
