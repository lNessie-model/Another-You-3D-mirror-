param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $projectRoot 'app\build\camera-preview-sdk-tests\classes'
$built=Join-Path $projectRoot 'app\build\intermediates\javac\debug\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$names=@('AvatarAsset','AvatarGlbLoader','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPackageStore',
    'AvatarPoseWorker','AvatarPoseProgressWatchdog','AvatarBatchLayout','AvatarBatchGpu','AvatarGpuScene','AvatarPbrShaderVariant','ResourceCleanup','AvatarOrmUploadPolicy','AvatarDrawPartition','MultiviewGl','PersistentMultiviewFbos','PersistentMultiviewGl','AvatarCameraProjectionCache',
    'FaceFrame','FaceControlCalibration','FaceControlMapper','InteractionController',
    'CameraPreviewSession','CameraPreviewPose','CameraPreviewAsset','CameraCalibrationAvatarPreview')
$names+=@('AvatarBackgroundCache','AvatarBackgroundGl','BundledAvatarCatalog','BundledAvatarSelection','SceneViewSettings')
$sources=$names | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Camera preview real SDK compilation failed'}
# Freshly compile the production method compared by reflection. Built classes only link unrelated Activity dependencies.
$sources=@((Join-Path $projectRoot 'app\src\main\java\com\mirror\bench\RuntimeGlLifecycle.java'),(Join-Path $projectRoot 'app\src\main\java\com\mirror\bench\InterlaceRenderer.java'),(Join-Path $projectRoot 'tests\CameraPreviewPoseParityTest.java'))
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$classes;$AndroidJar;$built" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Camera preview production parity compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$AndroidJar;$built" com.mirror.bench.CameraPreviewPoseParityTest
if($LASTEXITCODE -ne 0){throw 'Camera preview production pose parity failed'}
Write-Host 'Real Android SDK APIs compile. These host tests do not execute EGL, View lifecycle or artwork.'
