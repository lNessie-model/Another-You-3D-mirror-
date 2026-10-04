param([string]$JavaHome='', [string]$AndroidSdk='', [string]$ProtobufJar='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $JavaHome){$JavaHome=$env:JAVA_HOME}
if($JavaHome){$javac=Join-Path $JavaHome 'bin\javac.exe';$java=Join-Path $JavaHome 'bin\java.exe'}
else{$javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source}
if(-not $AndroidSdk){
 $sdkLine=Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') | Where-Object {$_ -match '^sdk\.dir='} | Select-Object -First 1
 if($sdkLine){$AndroidSdk=$sdkLine.Substring(8).Replace('\:',':').Replace('\\','\')}
}
$androidJar=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if(-not $ProtobufJar){
 $cache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.protobuf\protobuf-javalite\4.26.1'
 $ProtobufJar=Get-ChildItem -LiteralPath $cache -Recurse -Filter 'protobuf-javalite-4.26.1.jar' | Select-Object -First 1 -ExpandProperty FullName
}
$coreJar=Join-Path $projectRoot 'tools\tasks-core-classes.jar'
$visionJar=Join-Path $projectRoot 'tools\tasks-vision-classes.jar'
foreach($dependency in @($androidJar,$ProtobufJar,$coreJar,$visionJar)){if(-not(Test-Path -LiteralPath $dependency)){throw "Missing real compile dependency: $dependency"}}
$classes=Join-Path $projectRoot 'app\build\camera-controls-tests'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$cp="$androidJar;$ProtobufJar;$coreJar;$visionJar"
$sources=@('CameraControlSettings','MirrorSettings','PanelCalibration','FaceFrame','FaceControlCalibration','FaceControlMapper','InteractionController','FacePoseMatrix','FacePostGraph','ResourceCleanup','BlendshapeSchema') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=@('CameraControlSettingsTest','MirrorSettingsTest','ControllerCalibrationTest','InteractionControllerTest','FacePoseMatrixTest') | ForEach-Object {Join-Path $PSScriptRoot "$_.java"}
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Camera control source compilation failed.'}
foreach($test in @('CameraControlSettingsTest','MirrorSettingsTest','ControllerCalibrationTest','InteractionControllerTest','FacePoseMatrixTest')){
 & $java -cp "$classes;$cp" "com.mirror.bench.$test"
 if($LASTEXITCODE -ne 0){throw "$test failed."}
}
Write-Host 'Real SDK/protobuf source linkage + host control tests passed; no APK, native graph or ADB execution.'
