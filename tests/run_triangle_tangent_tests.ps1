param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidJar='C:/Users/lNessie/AppData/Local/Android/Sdk/platforms/android-35/android.jar',[string]$Stage='math')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$built=Join-Path $root 'app/build/intermediates/javac/debug/classes'
$out=Join-Path $root ('app/build/triangle-tangent-tests/'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $out | Out-Null
Write-Output "Evidence: $out"
$names=@('AvatarGpuScene','TriangleTangentTable','TriangleTangentShader','TriangleTangentGl','TriangleTangentCheck','TriangleTangentCheckActivity')
$sources=$names|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$AndroidJar;$built" -d $out @sources (Join-Path $root 'tests/TriangleTangentTableTest.java') 2>&1|Tee-Object -FilePath (Join-Path $out 'compile.log')
if($LASTEXITCODE -ne 0){throw "Tangent compile failed: $out"}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$json;$AndroidJar;$built" com.mirror.bench.TriangleTangentTableTest 2>&1|Tee-Object -FilePath (Join-Path $out 'math.log')
if($LASTEXITCODE -ne 0){throw 'Tangent math failed'}
$contract=Join-Path $out 'contract';New-Item -ItemType Directory -Path $contract|Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $contract (Join-Path $root 'tests/persistent-fbo-stubs/android/os/Bundle.java') (Join-Path $root 'tests/TriangleTangentContractTest.java')
if($LASTEXITCODE -ne 0){throw 'Tangent contract compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$contract;$out;$json;$AndroidJar;$built" com.mirror.bench.TriangleTangentContractTest 2>&1|Tee-Object -FilePath (Join-Path $out 'contract.log')
if($LASTEXITCODE -ne 0){throw 'Tangent diagnostic contract failed'}

if($Stage -eq 'scene'){
 $boundary=Join-Path $out 'scene-boundary';New-Item -ItemType Directory -Path $boundary|Out-Null
 $fixture=@('tests/triangle-tangent-stubs/android/opengl/GLES30.java','tests/individual-reference-stubs/android/opengl/Matrix.java','tests/individual-reference-stubs/android/opengl/GLUtils.java','tests/orm-upload-stubs/android/graphics/Bitmap.java','tests/orm-upload-stubs/android/graphics/BitmapFactory.java','tests/TriangleTangentSceneTest.java')|ForEach-Object{Join-Path $root $_}
 & (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $boundary @fixture
 if($LASTEXITCODE -ne 0){throw 'Tangent Scene boundary compile failed'}
 & (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$out;$json;$AndroidJar;$built" com.mirror.bench.TriangleTangentSceneTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1|Tee-Object -FilePath (Join-Path $out 'scene.log')
 if($LASTEXITCODE -ne 0){throw 'Tangent actual Scene boundary failed'}
}
