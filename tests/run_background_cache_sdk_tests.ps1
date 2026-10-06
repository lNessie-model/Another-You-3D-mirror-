param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\background-cache-sdk-tests\classes'
$built=Join-Path $root 'app\build\intermediates\javac\debug\classes'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON-java test dependency'}
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$names=@('AvatarAsset','AvatarGlbLoader','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema',
    'AvatarPoseWorker','AvatarPoseProgressWatchdog','AvatarBatchLayout','AvatarBatchGpu','AvatarGpuScene','AvatarPbrShaderVariant','ResourceCleanup','AvatarOrmUploadPolicy','AvatarDrawPartition',
    'AvatarBackgroundCache','AvatarBackgroundGl','MultiviewGl','PersistentMultiviewFbos','PersistentMultiviewGl','AvatarCameraProjectionCache',
    'RuntimeGlLifecycle','RuntimeViewCount','RuntimeInputStop','NativeCleanupUnconfirmed','RuntimeStatusOrder','MirrorSettings','InterlaceRenderer','MirrorActivity','AvatarMultiviewCheck','AvatarPreviewActivity','AvatarOrmRg8Check','AvatarPbrFastMathCheck',
    'AvatarPixelComparison','AvatarPoseFixtures','AvatarCameraProjectionCheck','AvatarPersistentFboCheck','AvatarBatchCheck')
$sources=$names | ForEach-Object {Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$json;$AndroidJar;$built" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Background cache actual SDK compilation failed'}
$tests=@('tests\persistent-fbo-stubs\android\os\Bundle.java','tests\stubs\android\os\SystemClock.java','tests\BackgroundCacheConfigTest.java') | ForEach-Object {Join-Path $root $_}
$tests+=Join-Path $root 'tests\AvatarBackgroundStateTest.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$classes;$json;$AndroidJar;$built" -d $classes @tests
if($LASTEXITCODE -ne 0){throw 'Background cache config test compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json;$AndroidJar;$built" com.mirror.bench.BackgroundCacheConfigTest
if($LASTEXITCODE -ne 0){throw 'Background cache config checks failed'}
$modelRoot='E:\tripo\assets\mirror-models-20261003'
$sceneFolders=@("$modelRoot\lowpoly-heads-v2\geralt-gothic-unlit","$modelRoot\lowpoly-heads-v2\makima-cyber-unlit","$modelRoot\lowpoly-film-v1\maul-gothic-unlit")
foreach($folder in $sceneFolders){if(-not (Test-Path -LiteralPath (Join-Path $folder 'character.glb'))){throw "Real background scene absent: $folder"}}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json;$AndroidJar;$built" com.mirror.bench.AvatarBackgroundStateTest @sceneFolders
if($LASTEXITCODE -ne 0){throw 'Background cache real scene/pose key checks failed'}
Write-Host 'Actual GLES SDK APIs compiled, shaders/GPU output/performance are not executed or qualified.'
