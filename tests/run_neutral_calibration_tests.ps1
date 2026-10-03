param([string]$JavaHome='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $JavaHome){$JavaHome=$env:JAVA_HOME}
if($JavaHome){$javac=Join-Path $JavaHome 'bin\javac.exe';$java=Join-Path $JavaHome 'bin\java.exe'}
else{$javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source}
$classes=Join-Path $projectRoot 'app\build\neutral-calibration-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('FaceFrame','FaceControlCalibration','FaceControlMapper','NeutralCalibrationCollector') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=@('NeutralCalibrationCollectorTest','NeutralRotationMeanTest') | ForEach-Object {Join-Path $PSScriptRoot "$_.java"}
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Neutral calibration compilation failed.'}
foreach($test in @('NeutralCalibrationCollectorTest','NeutralRotationMeanTest')){
 & $java -cp $classes "com.mirror.bench.$test"
 if($LASTEXITCODE -ne 0){throw "$test failed."}
}
Write-Host 'Pure Java collector tests passed; no SDK, APK, ADB or camera/NPU execution.'
