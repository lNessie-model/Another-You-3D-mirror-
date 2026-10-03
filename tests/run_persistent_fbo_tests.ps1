param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\persistent-fbo-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@((Join-Path $root 'app\src\main\java\com\mirror\bench\PersistentMultiviewFbos.java'),(Join-Path $root 'tests\PersistentMultiviewFbosTest.java'))
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Persistent FBO host compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.PersistentMultiviewFbosTest
if($LASTEXITCODE -ne 0){throw 'Persistent FBO host tests failed'}
