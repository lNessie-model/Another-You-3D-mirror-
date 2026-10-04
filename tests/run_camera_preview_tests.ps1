param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $projectRoot 'app\build\camera-preview-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('FaceFrame.java','CameraPreviewSession.java','CameraPreviewPose.java') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_"}
$sources+=Join-Path $projectRoot 'tests\CameraPreviewStateTest.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Camera preview state compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.CameraPreviewStateTest
if($LASTEXITCODE -ne 0){throw 'Camera preview state checks failed'}
