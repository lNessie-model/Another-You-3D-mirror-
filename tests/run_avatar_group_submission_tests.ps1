param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidSdk='C:/Users/lNessie/AppData/Local/Android/Sdk',[string]$BaselineSource='')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$android=Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$run=Join-Path $root ('app/build/avatar-group-submission-tests/'+[guid]::NewGuid())
$classes=Join-Path $run 'classes';New-Item -ItemType Directory -Force -Path $classes | Out-Null
Write-Output "Evidence: $run"
$names=@('AvatarAsset','AvatarGlbLoader','AvatarOrmUploadPolicy','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPoseWorker','AvatarGpuScene','AvatarDrawPartition','AvatarBatchLayout','AvatarBatchGpu','AvatarPoseProgressWatchdog','SceneViewSettings','AvatarPbrShaderVariant','AvatarBatchSpecializedGpu','AvatarPrimaryColorPolicy')
$sources=$names|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$android" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw "Actual SDK group-submission compile failed: $run"}
$cp="$classes;$json;$android"
$boundary=Join-Path $run 'boundary';New-Item -ItemType Directory -Path $boundary | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $boundary (Join-Path $root 'tests/group-submission-stubs/android/opengl/GLES30.java') (Join-Path $root 'tests/AvatarGroupSubmissionGlTest.java') 2>&1 | Tee-Object -FilePath (Join-Path $run 'boundary-compile.log')
if($LASTEXITCODE -ne 0){throw 'Group-submission GL boundary harness compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$boundary;$cp" com.mirror.bench.AvatarGroupSubmissionGlTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1 | Tee-Object -FilePath (Join-Path $run 'behavior.log')
if($LASTEXITCODE -ne 0){throw 'Group-submission GL behavior test failed'}
if($BaselineSource){
 $baseline=Join-Path $run 'baseline';$baselineSrc=Join-Path $baseline 'src';$baselineClasses=Join-Path $baseline 'classes';$emptySource=Join-Path $baseline 'empty-source'
 New-Item -ItemType Directory -Path $baselineSrc,$baselineClasses,$emptySource | Out-Null
 $source=Join-Path $baselineSrc 'AvatarBatchSpecializedGpu.java';Copy-Item -LiteralPath $BaselineSource -Destination $source
 & (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath $emptySource -cp $cp -d $baselineClasses $source 2>&1 | Tee-Object -FilePath (Join-Path $run 'baseline-compile.log')
 if($LASTEXITCODE -ne 0){throw 'Frozen V46 class compile failed'}
 $args=@('-cp',"$boundary;$cp",'com.mirror.bench.AvatarGroupSubmissionGlTest',(Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb'),(Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json'),'--legacy-trace')
 & (Join-Path $JavaHome 'bin/java.exe') @args 2>&1 | Tee-Object -FilePath (Join-Path $run 'default-current-trace.log')
 if($LASTEXITCODE -ne 0){throw 'Current default trace failed'}
 $args[1]="$baselineClasses;$boundary;$cp"
 & (Join-Path $JavaHome 'bin/java.exe') @args 2>&1 | Tee-Object -FilePath (Join-Path $run 'default-v46-trace.log')
 if($LASTEXITCODE -ne 0){throw 'Frozen V46 default trace failed'}
 if((Get-Content -LiteralPath (Join-Path $run 'default-current-trace.log') -Raw) -cne (Get-Content -LiteralPath (Join-Path $run 'default-v46-trace.log') -Raw)){throw 'Default API trace differs from frozen V46'}
}
Write-Output 'Actual SDK compile and production Java GL-boundary behavior only; no Mali/pixel/performance claim.'
