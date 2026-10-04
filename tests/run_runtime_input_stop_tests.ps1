param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\runtime-input-stop-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('app\src\main\java\com\mirror\bench\RuntimeStatusOrder.java',
 'app\src\main\java\com\mirror\bench\RuntimeInputStop.java','tests\RuntimeInputStopTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Runtime input stop compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $classes com.mirror.bench.RuntimeInputStopTest
if($LASTEXITCODE -ne 0){throw 'Runtime input stop checks failed'}
