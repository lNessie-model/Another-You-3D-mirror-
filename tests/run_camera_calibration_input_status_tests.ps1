param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$built=Join-Path $root 'app\build\intermediates\javac\debug\classes';$classes=Join-Path $root 'app\build\camera-calibration-input-status-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $built -d $classes (Join-Path $root 'app\src\main\java\com\mirror\bench\CameraCalibrationInputStatus.java') (Join-Path $root 'tests\CameraCalibrationInputStatusTest.java')
if($LASTEXITCODE -ne 0){throw 'Camera input status compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$built" com.mirror.bench.CameraCalibrationInputStatusTest
if($LASTEXITCODE -ne 0){throw 'Camera input status checks failed'}
