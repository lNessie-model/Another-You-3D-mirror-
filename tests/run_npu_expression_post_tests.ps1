param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidSdk='',[string]$ProtobufJar='')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(-not $AndroidSdk){
 $line=Get-Content -LiteralPath (Join-Path $root 'local.properties') | Where-Object {$_ -match '^sdk\.dir='} | Select-Object -First 1
 if($line){$AndroidSdk=$line.Substring(8).Replace('\:',':').Replace('\\','\')}
}
if(-not $ProtobufJar){
 $cache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.protobuf\protobuf-javalite\4.26.1'
 $ProtobufJar=Get-ChildItem -LiteralPath $cache -Recurse -Filter 'protobuf-javalite-4.26.1.jar' | Select-Object -First 1 -ExpandProperty FullName
}
$android=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Real JSON library pin mismatch.'}
$deps=@($json,$android,$ProtobufJar,(Join-Path $root 'tools\tasks-core-classes.jar'),(Join-Path $root 'tools\tasks-vision-classes.jar'))
foreach($dep in $deps){if(-not(Test-Path -LiteralPath $dep)){throw "Missing actual dependency: $dep"}}
$cp=$deps -join ';'
$classes=Join-Path $root 'app\build\npu-expression-post-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('FaceGeometryPostGraph','NpuExpressionPostGraph','NormalizedBlendshapeInput','RknnExpression','FacePostGraph','FacePoseMatrix','ResourceCleanup','BlendshapeSchema') | ForEach-Object {Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=@('FaceGeometryPostGraphTest','NpuExpressionPostGraphTest') | ForEach-Object {Join-Path $PSScriptRoot "$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Expression composition SDK/protobuf compilation failed.'}
foreach($name in @('FaceGeometryPostGraphTest','NpuExpressionPostGraphTest')){
 & (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$cp" "com.mirror.bench.$name"
 if($LASTEXITCODE -ne 0){throw "Failed: $name"}
}
Write-Host 'Actual production composition + real protobuf configuration tested with explicit fake geometry/expression dependencies. No native/ADB execution.'
