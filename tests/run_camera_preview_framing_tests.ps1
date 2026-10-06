param([string]$JavaHome='C:\Program Files\Java\jdk-17',
      [string]$AndroidSdk='C:\Users\lNessie\AppData\Local\Android\Sdk',
      [string]$EvidenceDirectory='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'JSON dependency differs'}
$sdkJar=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if(!$EvidenceDirectory){$EvidenceDirectory=Join-Path $projectRoot ('app\build\camera-preview-framing-tests\runs\'+[Guid]::NewGuid().ToString())}
$classes=Join-Path $EvidenceDirectory 'classes'
if(Test-Path -LiteralPath $EvidenceDirectory){throw 'Framing evidence directory must be new'}
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$names=@('AvatarAsset','AvatarGlbLoader','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','CameraPreviewPose','CameraPreviewFraming','FaceFrame')
$sources=@($names|ForEach-Object{Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"})
$sources+=Join-Path $projectRoot 'tests\CameraPreviewFramingTest.java'
$hashes=@($sources|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$hashes|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'sources.json') -Encoding utf8
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$jar;$sdkJar" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'compile.log')
if($LASTEXITCODE -ne 0){throw 'Framing production CPU compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -Xmx768m -cp "$classes;$jar;$sdkJar" com.mirror.bench.CameraPreviewFramingTest (Join-Path $projectRoot 'app\src\main\assets') 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'geometry.log')
if($LASTEXITCODE -ne 0){throw 'Actual bundled calibration framing failed'}
Write-Output "Evidence: $EvidenceDirectory"
