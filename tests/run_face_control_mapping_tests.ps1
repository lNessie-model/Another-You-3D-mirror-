param([string]$JavaHome='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $JavaHome){$JavaHome=$env:JAVA_HOME}
if($JavaHome){$javac=Join-Path $JavaHome 'bin\javac.exe';$java=Join-Path $JavaHome 'bin\java.exe'}
else{$javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source}
$classes=Join-Path $projectRoot 'app\build\face-control-mapping-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('BlendshapeSchema','FaceControlCalibration','FaceControlMapper') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=Join-Path $PSScriptRoot 'FaceControlMapperTest.java'
$sources+=Join-Path $PSScriptRoot 'FaceControlBaselineTest.java'
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Face control mapping compilation failed.'}
& $java -cp $classes com.mirror.bench.FaceControlMapperTest
if($LASTEXITCODE -ne 0){throw 'Face control mapping tests failed.'}
& $java -cp $classes com.mirror.bench.FaceControlBaselineTest
if($LASTEXITCODE -ne 0){throw 'Face control baseline tests failed.'}
Write-Host 'Pure Java face-control tests passed; no Android SDK, APK build, ADB or device execution.'
