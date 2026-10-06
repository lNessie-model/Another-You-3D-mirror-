param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidSdk='C:/Users/lNessie/AppData/Local/Android/Sdk')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar';$android=Join-Path $AndroidSdk 'platforms/android-35/android.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$run=Join-Path $root ('app/build/avatar-individual-reference-tests/'+[guid]::NewGuid());$classes=Join-Path $run 'classes';$boundary=Join-Path $run 'boundary'
New-Item -ItemType Directory -Force -Path $classes,$boundary | Out-Null;Write-Output "Evidence: $run"
$sources=@('AvatarGpuScene','AvatarSpecializedCheck','AvatarBatchGpu','AvatarBatchSpecializedGpu')|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$android" -d $classes @sources 2>&1 | Tee-Object (Join-Path $run 'sdk-compile.log')
if($LASTEXITCODE -ne 0){throw 'Actual SDK Scene/checker compile failed'}
$cp="$classes;$json;$android"
$fixture=@('tests/individual-reference-stubs/android/opengl/GLES30.java','tests/individual-reference-stubs/android/opengl/Matrix.java','tests/individual-reference-stubs/android/opengl/GLUtils.java','tests/orm-upload-stubs/android/graphics/Bitmap.java','tests/orm-upload-stubs/android/graphics/BitmapFactory.java','tests/AvatarIndividualReferenceTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $boundary @fixture 2>&1 | Tee-Object (Join-Path $run 'boundary-compile.log')
if($LASTEXITCODE -ne 0){throw 'Actual implementation host boundary compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$cp" com.mirror.bench.AvatarIndividualReferenceTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1 | Tee-Object (Join-Path $run 'behavior.log')
if($LASTEXITCODE -ne 0){throw 'Actual Scene/checker behavior test failed'}
$reviews=@('AvatarRigTest','AvatarGpuSceneProgressReviewTest','AvatarGpuSceneMetricsReviewTest','AvatarGpuSceneStopReviewTest')|ForEach-Object{Join-Path $root "tests/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $classes @reviews 2>&1 | Tee-Object (Join-Path $run 'existing-review-compile.log')
if($LASTEXITCODE -ne 0){throw 'Existing Scene CPU review compile failed'}
foreach($name in @('AvatarGpuSceneProgressReviewTest','AvatarGpuSceneMetricsReviewTest','AvatarGpuSceneStopReviewTest')){
 & (Join-Path $JavaHome 'bin/java.exe') -cp $cp "com.mirror.bench.$name" 2>&1 | Tee-Object (Join-Path $run "$name.log")
 if($LASTEXITCODE -ne 0){throw "$name failed"}
}
Write-Output 'Production Java VERIFY/routing/counters/geometry/lifecycle passed at GL boundary; no actual GLSL/pixels/performance claim.'
