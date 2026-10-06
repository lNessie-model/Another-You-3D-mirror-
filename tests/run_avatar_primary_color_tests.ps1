param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidSdk='C:/Users/lNessie/AppData/Local/Android/Sdk')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$android=Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$run=Join-Path $root ('app/build/avatar-primary-color-tests/'+[guid]::NewGuid())
$classes=Join-Path $run 'classes';New-Item -ItemType Directory -Force -Path $classes | Out-Null
Write-Output "Evidence: $run"
$names=@('AvatarAsset','AvatarGlbLoader','AvatarOrmUploadPolicy','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPoseWorker','AvatarGpuScene','AvatarDrawPartition','AvatarBatchLayout','AvatarBatchGpu','AvatarPoseProgressWatchdog','SceneViewSettings','AvatarPbrShaderVariant','AvatarBatchSpecializedGpu')
$sources=$names|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
$policy=Join-Path $root 'app/src/main/java/com/mirror/bench/AvatarPrimaryColorPolicy.java'
if(Test-Path -LiteralPath $policy){$sources+=$policy}
$sources+=Join-Path $root 'tests/AvatarPrimaryColorPolicyTest.java'
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$android" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw "Actual SDK primary-color compile failed: $run"}
$cp="$classes;$json;$android"
& (Join-Path $JavaHome 'bin/java.exe') -cp $cp com.mirror.bench.AvatarPrimaryColorPolicyTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1 | Tee-Object -FilePath (Join-Path $run 'policy.log')
if($LASTEXITCODE -ne 0){throw 'Primary-color packed policy test failed'}
$boundary=Join-Path $run 'boundary';New-Item -ItemType Directory -Path $boundary | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $boundary (Join-Path $root 'tests/primary-color-stubs/android/opengl/GLES30.java') (Join-Path $root 'tests/AvatarPrimaryColorGlTest.java') 2>&1 | Tee-Object -FilePath (Join-Path $run 'boundary-compile.log')
if($LASTEXITCODE -ne 0){throw 'Primary-color GL boundary harness compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$boundary;$cp" com.mirror.bench.AvatarPrimaryColorGlTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1 | Tee-Object -FilePath (Join-Path $run 'gl-boundary.log')
if($LASTEXITCODE -ne 0){throw 'Primary-color GL behavior test failed'}
Write-Output 'Actual SDK and GL-boundary tests only; Mali shader, direct ordinary pixels and performance remain device gates.'
