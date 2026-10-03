param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\runtime-expression-backend-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $root 'app\src\main\java\com\mirror\bench\RuntimeExpressionBackend.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes $source
if($LASTEXITCODE -ne 0){throw 'Expression option actual SDK compile failed'}
$sources=@((Join-Path $PSScriptRoot 'persistent-fbo-stubs\android\os\Bundle.java'),$source,(Join-Path $PSScriptRoot 'RuntimeExpressionBackendTest.java'))
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Expression option host compile failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.RuntimeExpressionBackendTest
if($LASTEXITCODE -ne 0){throw 'Expression option tests failed'}
