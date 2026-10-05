param([string]$JavaHome='C:\Program Files\Java\jdk-17',
      [string]$AndroidSdk='C:\Users\lNessie\AppData\Local\Android\Sdk')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'JSON dependency differs'}
$sdkJar=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$run=Join-Path $projectRoot ('app\build\camera-preview-bundled-tests\runs\'+[Guid]::NewGuid().ToString())
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$names=@('AvatarAsset','AvatarGlbLoader','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema',
         'AvatarPackageStore','CameraPreviewAsset','BundledAvatarCatalog','BundledAvatarSelection')
$sources=@($names|ForEach-Object{Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"})
$sources+=@('tests\bundled-stubs\android\content\Context.java','tests\bundled-stubs\android\content\SharedPreferences.java',
            'tests\bundled-stubs\android\content\res\AssetManager.java','tests\BundledCatalogTestSupport.java',
            'tests\CameraPreviewBundledAssetTest.java')|ForEach-Object{Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$jar;$sdkJar" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Bundled calibration CPU compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -Xmx512m -cp "$classes;$jar;$sdkJar" com.mirror.bench.CameraPreviewBundledAssetTest (Join-Path $projectRoot 'app\src\main\assets') (Join-Path $run 'untouched-store') | Tee-Object -FilePath (Join-Path $run 'actual-assets.log')
if($LASTEXITCODE -ne 0){throw 'Actual bundled calibration assets failed'}
Write-Output "Evidence: $run"
