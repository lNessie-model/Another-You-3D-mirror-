param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\product-view-count-tests'
$built=Join-Path $root 'app\build\intermediates\javac\debug\classes'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$names=@('MirrorSettings','RuntimeViewCount','RuntimeInputStop','NativeCleanupUnconfirmed','RuntimeStatusOrder','MirrorActivity','CalibrationActivity','PanelPreviewActivity','PanelCalibration','CameraControlSettings','InterlaceRenderer')
$names+=@('AvatarBackgroundCache','AvatarBackgroundGl','AvatarGpuScene','AvatarPbrShaderVariant','AvatarBatchGpu','ResourceCleanup','AvatarOrmUploadPolicy','AvatarDrawPartition')
$sources=$names | ForEach-Object {Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$json;$AndroidJar;$built" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Product count actual SDK compilation failed'}
$tests=@('tests\product-view-count-stubs\android\app\Activity.java','tests\persistent-fbo-stubs\android\os\Bundle.java',
 'tests\view-count-stubs\android\content\Intent.java','tests\stubs\android\os\SystemClock.java','tests\ProductRuntimeViewCountTest.java') | ForEach-Object {Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$classes;$json;$AndroidJar;$built" -d $classes @tests
if($LASTEXITCODE -ne 0){throw 'Product count host test compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json;$AndroidJar;$built" com.mirror.bench.ProductRuntimeViewCountTest
if($LASTEXITCODE -ne 0){throw 'Product count host tests failed'}
Write-Host 'Actual SDK linkage/runtime methods/Intent factories + host prefs/recreation counter; no APK, UI, EGL or pixels.'
