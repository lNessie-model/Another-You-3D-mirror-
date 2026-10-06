param([string]$JavaHome='C:/Program Files/Java/jdk-17', [string]$AndroidSdk='C:/Users/lNessie/AppData/Local/Android/Sdk')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected real JSON dependency'}
$android=Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$run=Join-Path $root ('app/build/avatar-orm-tests/'+[guid]::NewGuid().ToString())
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('AvatarAsset','AvatarGlbLoader','AvatarOrmUploadPolicy','AvatarOrmRg8Check','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPoseWorker','AvatarGpuScene','AvatarDrawPartition','AvatarBatchLayout','AvatarBatchGpu','AvatarPoseProgressWatchdog','SceneViewSettings') | ForEach-Object {Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
$sources+=@('AvatarOrmUploadPolicyTest','AvatarOrmRg8CheckTest','AvatarPbrShaderTest','AvatarAlbedoShaderTest','AvatarRigTest','AvatarGpuSceneProgressReviewTest','AvatarGpuSceneMetricsReviewTest','AvatarGpuSceneStopReviewTest') | ForEach-Object {Join-Path $root "tests/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$android" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw "ORM production SDK compile failed: $run"}
$cp="$classes;$json;$android"
& (Join-Path $JavaHome 'bin/java.exe') -cp $cp com.mirror.bench.AvatarOrmUploadPolicyTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') 2>&1 | Tee-Object -FilePath (Join-Path $run 'policy.log')
if($LASTEXITCODE -ne 0){throw 'ORM policy/packing failed'}
foreach($test in @('AvatarOrmRg8CheckTest','AvatarPbrShaderTest','AvatarAlbedoShaderTest','AvatarGpuSceneProgressReviewTest','AvatarGpuSceneMetricsReviewTest','AvatarGpuSceneStopReviewTest')){
    & (Join-Path $JavaHome 'bin/java.exe') -cp $cp "com.mirror.bench.$test" 2>&1 | Tee-Object -FilePath (Join-Path $run "$test.log")
    if($LASTEXITCODE -ne 0){throw "$test failed"}
}
$boundaries=Join-Path $run 'boundaries'
New-Item -ItemType Directory -Force -Path $boundaries | Out-Null
$fixtures=Get-ChildItem -LiteralPath (Join-Path $root 'tests/orm-upload-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName
$fixtures+=Join-Path $root 'tests/AvatarOrmSceneUploadTest.java'
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $boundaries @fixtures 2>&1 | Tee-Object -FilePath (Join-Path $run 'boundaries-compile.log')
if($LASTEXITCODE -ne 0){throw 'ORM host boundaries failed to compile'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$boundaries;$cp" com.mirror.bench.AvatarOrmSceneUploadTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') 2>&1 | Tee-Object -FilePath (Join-Path $run 'upload.log')
if($LASTEXITCODE -ne 0){throw 'ORM production upload boundary tests failed'}
Write-Output "PASS production SDK linkage + real asset/pure CPU contracts. No GPU execution. Evidence: $run"
