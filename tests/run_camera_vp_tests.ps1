param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\camera-vp-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('tests\camera-vp-stubs\android\opengl\Matrix.java','app\src\main\java\com\mirror\bench\SceneViewSettings.java','app\src\main\java\com\mirror\bench\AvatarCameraProjectionCache.java','tests\AvatarCameraProjectionCacheTest.java') | ForEach-Object {Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Camera VP host compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.AvatarCameraProjectionCacheTest
if($LASTEXITCODE -ne 0){throw 'Camera VP host tests failed'}
