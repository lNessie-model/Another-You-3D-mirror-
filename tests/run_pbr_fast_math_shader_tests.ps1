param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidSdk='C:/Users/lNessie/AppData/Local/Android/Sdk')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$android=Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$run=Join-Path $root ('app/build/pbr-fast-math-shader-tests/'+[guid]::NewGuid())
$classes=Join-Path $run 'classes';New-Item -ItemType Directory -Force -Path $classes | Out-Null
Write-Output "Evidence: $run"
$names=@('AvatarAsset','AvatarGlbLoader','AvatarOrmUploadPolicy','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPoseWorker','AvatarGpuScene','AvatarDrawPartition','AvatarBatchLayout','AvatarBatchGpu','AvatarPoseProgressWatchdog','SceneViewSettings')
$sources=$names|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
$variant=Join-Path $root 'app/src/main/java/com/mirror/bench/AvatarPbrShaderVariant.java'
if(Test-Path -LiteralPath $variant){$sources+=$variant}
$sources+=@('AvatarPbrFastMathTest','AvatarPbrShaderTest','AvatarBatchShaderTest','AvatarAlbedoShaderTest')|ForEach-Object{Join-Path $root "tests/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$android" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw "Actual SDK shader compile failed: $run"}
$cp="$classes;$json;$android"
foreach($name in @('AvatarPbrFastMathTest','AvatarPbrShaderTest','AvatarBatchShaderTest','AvatarAlbedoShaderTest')){
 & (Join-Path $JavaHome 'bin/java.exe') -cp $cp "com.mirror.bench.$name" 2>&1 | Tee-Object -FilePath (Join-Path $run "$name.log")
 if($LASTEXITCODE -ne 0){throw "$name failed: $run"}
}
$boundary=Join-Path $run 'boundary';New-Item -ItemType Directory -Path $boundary | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $boundary (Join-Path $root 'tests/pbr-shader-stubs/android/opengl/GLES30.java') (Join-Path $root 'tests/AvatarPbrProgramOwnershipTest.java') 2>&1 | Tee-Object -FilePath (Join-Path $run 'boundary-compile.log')
if($LASTEXITCODE -ne 0){throw "GL boundary harness compile failed: $run"}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$boundary;$cp" com.mirror.bench.AvatarPbrProgramOwnershipTest 2>&1 | Tee-Object -FilePath (Join-Path $run 'ownership.log')
if($LASTEXITCODE -ne 0){throw "GL boundary ownership failed: $run"}
Write-Output 'Actual SDK linkage/source/scalar checks passed. No GLSL compiler, GPU execution, APK, ADB or FPS claim.'
