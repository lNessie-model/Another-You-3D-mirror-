param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidJar='C:/Users/lNessie/AppData/Local/Android/Sdk/platforms/android-35/android.jar',[string]$BaselineSource='')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root ('app/build/srgb-view-config-tests/'+[guid]::NewGuid());New-Item -ItemType Directory -Force -Path $out|Out-Null;Write-Output "Evidence: $out"
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar';$built=Join-Path $root 'app/build/intermediates/javac/debug/classes'
$sources=@('MirrorActivity','InterlaceRenderer','AvatarGpuScene','SrgbViewPolicy','SrgbViewGl','AvatarSrgbCheck','AvatarPreviewActivity')|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$AndroidJar;$built" -d $out @sources 2>&1|Tee-Object -FilePath (Join-Path $out 'compile.log')
if($LASTEXITCODE -ne 0){throw 'Actual sRGB config compile failed'}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $out (Join-Path $root 'tests/persistent-fbo-stubs/android/os/Bundle.java') (Join-Path $root 'tests/SrgbViewConfigTest.java') (Join-Path $root 'tests/SrgbDiagnosticContractTest.java') 2>&1|Tee-Object -FilePath (Join-Path $out 'boundary-compile.log')
if($LASTEXITCODE -ne 0){throw 'sRGB config harness compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbViewConfigTest 2>&1|Tee-Object -FilePath (Join-Path $out 'behavior.log')
if($LASTEXITCODE -ne 0){throw 'sRGB config failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbDiagnosticContractTest 2>&1|Tee-Object -FilePath (Join-Path $out 'diagnostic-contract.log')
if($LASTEXITCODE -ne 0){throw 'sRGB diagnostic contract failed'}
$boundary=Join-Path $out 'renderer-boundary';New-Item -ItemType Directory -Path $boundary|Out-Null
$fixture=@('tests/srgb-render-stubs/android/opengl/GLES30.java','tests/individual-reference-stubs/android/opengl/Matrix.java','tests/individual-reference-stubs/android/opengl/GLUtils.java','tests/orm-upload-stubs/android/graphics/Bitmap.java','tests/orm-upload-stubs/android/graphics/BitmapFactory.java','tests/stubs/android/os/SystemClock.java','tests/SrgbRendererBoundaryTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $boundary @fixture 2>&1|Tee-Object -FilePath (Join-Path $out 'renderer-compile.log')
if($LASTEXITCODE -ne 0){throw 'sRGB renderer fixture compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbRendererBoundaryTest (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb') (Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json') 2>&1|Tee-Object -FilePath (Join-Path $out 'renderer.log')
if($LASTEXITCODE -ne 0){throw 'sRGB actual renderer fixture failed'}
if($BaselineSource){
 $baseline=Join-Path $out 'baseline';$empty=Join-Path $out 'empty';New-Item -ItemType Directory -Path $baseline,$empty|Out-Null
 $parents=@('AvatarGpuScene.java','InterlaceRenderer.java')|ForEach-Object{Join-Path $BaselineSource $_}
 & (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath $empty -cp "$out;$json;$AndroidJar;$built" -d $baseline @parents 2>&1|Tee-Object -FilePath (Join-Path $out 'baseline-compile.log')
 if($LASTEXITCODE -ne 0){throw 'Parent byte source compile failed'}
 & (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$boundary;$out;$json;$AndroidJar;$built" -d $boundary (Join-Path $root 'tests/SrgbDefaultTraceTest.java')
 if($LASTEXITCODE -ne 0){throw 'Default trace fixture compile failed'}
 $traceArgs=@((Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/character.glb'),(Join-Path $root 'app/src/main/assets/avatars/catalog/geralt/avatar.json'))
 $before=& (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$baseline;$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbDefaultTraceTest @traceArgs
 if($LASTEXITCODE -ne 0){throw 'Parent default trace failed'}
 $after=& (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbDefaultTraceTest @traceArgs
 if($LASTEXITCODE -ne 0){throw 'Current default trace failed'}
 "parent=$before`ncurrent=$after"|Tee-Object -FilePath (Join-Path $out 'default-trace.log')
 if($before -ne $after){throw 'Default shader/GL call snapshots changed'}
}
