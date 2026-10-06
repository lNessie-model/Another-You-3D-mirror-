param([string]$JavaHome='', [string]$AndroidSdk='', [string]$JsonJar='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { $JavaHome=$env:JAVA_HOME }
if ($JavaHome) { $javac=Join-Path $JavaHome 'bin\javac.exe';$java=Join-Path $JavaHome 'bin\java.exe' }
else { $javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source }
if (-not $AndroidSdk) { $AndroidSdk=$env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk=$env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) {
    $line=Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $AndroidSdk=$line.Substring(8).Replace('\:',':').Replace('\\','\') }
}
$androidJar=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Android SDK 35 linkage jar is required.' }
if (-not $JsonJar) { $JsonJar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar' }
if ((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Real JSON-java 20240303 checksum mismatch.' }
$classes=Join-Path $projectRoot 'app\build\avatar-management-tests\sdk-classes'
$testClasses=Join-Path $projectRoot 'app\build\avatar-management-tests\host-test-classes'
New-Item -ItemType Directory -Force -Path $classes,$testClasses | Out-Null
if (Test-Path -LiteralPath (Join-Path $classes 'android')) { throw 'Production output contains an Android substitute; SDK linkage must stay isolated.' }
# Phase one: only production sources, linked against the actual SDK. No boundary substitute.
$productionNames=@('AvatarManagementActivity','AvatarManagementGate','AvatarPackageStore','AvatarAsset','AvatarRig','AvatarGlbLoader','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPoseWorker','AvatarGpuScene','AvatarOrmUploadPolicy','AvatarDrawPartition','AvatarPoseProgressWatchdog','AvatarBatchGpu','AvatarBatchLayout',
    'BundledAvatarCatalog','BundledAvatarSelection','MultiviewGl','PersistentMultiviewFbos','PersistentMultiviewGl',
    'AvatarCameraProjectionCache','AvatarBackgroundCache','AvatarBackgroundGl','SceneViewSettings')
$productionSources=$productionNames | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"}
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$JsonJar;$androidJar" -d $classes @productionSources
if ($LASTEXITCODE -ne 0) { throw 'Avatar management SDK linkage compilation failed.' }
# Phase two: actual Store/parser bytecode, with only Android's filesystem boundary replaced on host.
$testSources=@('AvatarManagementGateTest','AvatarManagementDeadlineTest','AvatarManagementCatalogTest','AvatarPackageStoreTest') | ForEach-Object {Join-Path $PSScriptRoot "$_.java"}
$testSources+=Join-Path $PSScriptRoot 'stubs\android\os\StatFs.java'
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$classes;$JsonJar;$androidJar" -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw 'Avatar management host-boundary test compilation failed.' }
& $java -cp "$testClasses;$classes" com.mirror.bench.AvatarManagementGateTest
if ($LASTEXITCODE -ne 0) { throw 'Avatar management lifecycle gate tests failed.' }
& $java -cp "$testClasses;$classes" com.mirror.bench.AvatarManagementDeadlineTest
if ($LASTEXITCODE -ne 0) { throw 'Avatar management deadline tests failed.' }
& $java -cp "$testClasses;$classes;$JsonJar;$androidJar" com.mirror.bench.AvatarManagementCatalogTest (Join-Path $projectRoot 'app\build\avatar-management-tests\runs')
if ($LASTEXITCODE -ne 0) { throw 'Avatar management catalog regression failed.' }
Write-Host 'Production SDK linkage and separate host tests passed. Host Catalog uses only a StatFs boundary substitute. No Android lifecycle, GL, document-provider, ADB or device execution occurred.'
