param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidJar='C:/Users/lNessie/AppData/Local/Android/Sdk/platforms/android-35/android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar';$built=Join-Path $root 'app/build/intermediates/javac/debug/classes'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
$out=Join-Path $root ('app/build/srgb-runtime-entry-tests/'+[guid]::NewGuid());$boundary=Join-Path $out 'boundary';New-Item -ItemType Directory -Force -Path $out,$boundary|Out-Null;Write-Output "Evidence: $out"
$sources=@('InterlaceRenderer','AvatarGpuScene','SrgbViewPolicy','SrgbViewGl','PersistentMultiviewFbos','PersistentMultiviewGl')|ForEach-Object{Join-Path $root "app/src/main/java/com/mirror/bench/$_.java"}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath (Join-Path $root 'app/src/main/java') -cp "$json;$AndroidJar;$built" -d $out @sources 2>&1|Tee-Object (Join-Path $out 'sdk.log')
if($LASTEXITCODE -ne 0){throw 'Runtime source SDK compile failed'}
$fixtures=@('tests/srgb-runtime-stubs/android/opengl/GLES30.java','tests/srgb-runtime-stubs/android/content/res/AssetManager.java','tests/srgb-runtime-stubs/android/util/Log.java','tests/srgb-runtime-stubs/com/mirror/bench/MultiviewGl.java','tests/individual-reference-stubs/android/opengl/Matrix.java','tests/individual-reference-stubs/android/opengl/GLUtils.java','tests/orm-upload-stubs/android/graphics/Bitmap.java','tests/orm-upload-stubs/android/graphics/BitmapFactory.java','tests/stubs/android/os/SystemClock.java','tests/SrgbRuntimeEntryTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$out;$json;$AndroidJar;$built" -d $boundary @fixtures 2>&1|Tee-Object (Join-Path $out 'boundary-compile.log')
if($LASTEXITCODE -ne 0){throw 'Runtime boundary compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx768m -cp "$boundary;$out;$json;$AndroidJar;$built" com.mirror.bench.SrgbRuntimeEntryTest (Join-Path $root 'app/src/main/assets') 2>&1|Tee-Object (Join-Path $out 'behavior.log')
if($LASTEXITCODE -ne 0){throw 'Actual runtime entry test failed'}
