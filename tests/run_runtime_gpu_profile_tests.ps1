param([string]$JavaHome='C:/Program Files/Java/jdk-17', [string]$AndroidJar='C:/Users/lNessie/AppData/Local/Android/Sdk/platforms/android-35/android.jar')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root 'app/build/runtime-gpu-profile-tests'
$source=Join-Path $root 'app/src/main/java/com/mirror/bench'
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON-java test dependency'}
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources=@('GpuTimerProbe.java','Gles30GpuTimerBackend.java','RuntimeGpuProfile.java','SceneViewSettings.java') | ForEach-Object {Join-Path $source $_}
$sources+=@('GpuTimerProbeTest.java','RuntimeGpuFinalProfileTest.java') | ForEach-Object {Join-Path $PSScriptRoot $_}
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$AndroidJar;$json" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Production GPU timer compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$AndroidJar" com.mirror.bench.GpuTimerProbeTest
if($LASTEXITCODE -ne 0){throw 'GPU scheduler tests failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$json;$AndroidJar" com.mirror.bench.RuntimeGpuFinalProfileTest
if($LASTEXITCODE -ne 0){throw 'Runtime GPU wrapper tests failed'}
$adapter=Join-Path $out 'adapter'
New-Item -ItemType Directory -Force -Path $adapter | Out-Null
$fake=@('gpu-timer-stubs/android/opengl/EGLContext.java','gpu-timer-stubs/android/opengl/EGL14.java','gpu-timer-stubs/android/opengl/GLES30.java','GpuTimerAdapterTest.java') | ForEach-Object {Join-Path $PSScriptRoot $_}
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $out -d $adapter @fake
if($LASTEXITCODE -ne 0){throw 'Adapter test compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$adapter;$out" com.mirror.bench.GpuTimerAdapterTest
if($LASTEXITCODE -ne 0){throw 'GPU adapter call-order tests failed'}
