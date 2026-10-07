param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidJar='C:/Users/lNessie/AppData/Local/Android/Sdk/platforms/android-35/android.jar',[string]$Stage='policy')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$built=Join-Path $root 'app/build/intermediates/javac/debug/classes'
$out=Join-Path $root ('app/build/srgb-view-tests/'+[guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $out | Out-Null
Write-Output "Evidence: $out"
$names=@('SrgbViewPolicy','AvatarGpuScene')
if($Stage -in @('gl','scene')){$names+='SrgbViewGl'}
$sources=$names|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$AndroidJar;$built" -d $out @sources (Join-Path $root 'tests/SrgbViewPolicyTest.java') 2>&1|Tee-Object -FilePath (Join-Path $out 'compile.log')
if($LASTEXITCODE -ne 0){throw "sRGB production compile failed: $out"}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbViewPolicyTest 2>&1|Tee-Object -FilePath (Join-Path $out 'policy.log')
if($LASTEXITCODE -ne 0){throw 'sRGB policy failed'}
if($Stage -in @('gl','scene')){
 $boundary=Join-Path $out 'boundary';New-Item -ItemType Directory -Path $boundary|Out-Null
 & (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $boundary (Join-Path $root 'tests/srgb-view-stubs/android/opengl/GLES30.java') (Join-Path $root 'tests/SrgbViewGlTest.java')
 if($LASTEXITCODE -ne 0){throw 'sRGB GL harness compile failed'}
 & (Join-Path $JavaHome 'bin/java.exe') -cp "$boundary;$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbViewGlTest 2>&1|Tee-Object -FilePath (Join-Path $out 'gl.log')
 if($LASTEXITCODE -ne 0){throw 'sRGB GL boundary failed'}
}
if($Stage -eq 'scene'){
 $boundary=Join-Path $out 'scene-boundary';New-Item -ItemType Directory -Path $boundary|Out-Null
 $fixture=@('tests/individual-reference-stubs/android/opengl/GLES30.java','tests/individual-reference-stubs/android/opengl/Matrix.java','tests/individual-reference-stubs/android/opengl/GLUtils.java','tests/orm-upload-stubs/android/graphics/Bitmap.java','tests/orm-upload-stubs/android/graphics/BitmapFactory.java','tests/AvatarSrgbSceneTest.java')|ForEach-Object{Join-Path $root $_}
 & (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $boundary @fixture
 if($LASTEXITCODE -ne 0){throw 'sRGB Scene boundary compile failed'}
 & (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$out;$json;$AndroidJar;$built" com.mirror.bench.AvatarSrgbSceneTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1|Tee-Object -FilePath (Join-Path $out 'scene.log')
 if($LASTEXITCODE -ne 0){throw 'sRGB actual Scene boundary failed'}
}
