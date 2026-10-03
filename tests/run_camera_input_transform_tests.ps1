param([string]$JavaHome='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $JavaHome){$JavaHome=$env:JAVA_HOME}
if($JavaHome){$javac=Join-Path $JavaHome 'bin\javac.exe';$java=Join-Path $JavaHome 'bin\java.exe'}
else{$javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source}
$classes=Join-Path $projectRoot 'app\build\camera-input-transform-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& $javac --release 17 -encoding UTF-8 -d $classes (Join-Path $projectRoot 'app\src\main\java\com\mirror\bench\CameraInputTransform.java') (Join-Path $PSScriptRoot 'CameraInputTransformTest.java')
if($LASTEXITCODE -ne 0){throw 'Camera input transform compilation failed.'}
& $java -cp $classes com.mirror.bench.CameraInputTransformTest
if($LASTEXITCODE -ne 0){throw 'Camera input transform tests failed.'}
