param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $projectRoot 'app\build\camera-bitmap-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=@('tests\bitmap-stubs\android\graphics\Bitmap.java','app\src\main\java\com\mirror\bench\CameraInputTransform.java','app\src\main\java\com\mirror\bench\CameraBitmapNormalizer.java','tests\CameraBitmapNormalizerTest.java') | ForEach-Object {Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @source
if($LASTEXITCODE -ne 0){throw 'Normalizer compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.CameraBitmapNormalizerTest
if($LASTEXITCODE -ne 0){throw 'Normalizer checks failed'}
