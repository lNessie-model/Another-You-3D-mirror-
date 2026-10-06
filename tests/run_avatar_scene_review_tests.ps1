param(
    [string]$JavaHome = '',
    [string]$AndroidSdk = '',
    [string]$JsonJar = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JsonJar) { $JsonJar = Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar' }
if (-not (Test-Path -LiteralPath $JsonJar)) { throw 'Real JSON-java 20240303 jar required; see tests/avatar-loader.md.' }
if ((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') {
    throw 'Unexpected JSON-java SHA256.'
}
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) {
    $properties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $AndroidSdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
}
if (-not $AndroidSdk) { throw 'Pass -AndroidSdk or configure Android SDK 35 in local.properties.' }
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Android SDK linkage jar missing: $androidJar" }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) { $javac = Join-Path $JavaHome 'bin\javac.exe'; $java = Join-Path $JavaHome 'bin\java.exe' }
else { $javac = (Get-Command javac -ErrorAction Stop).Source; $java = (Get-Command java -ErrorAction Stop).Source }
foreach ($tool in @($javac, $java)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Java 17 compiler/runtime missing: $tool" }
}
$classes = Join-Path $projectRoot 'app\build\avatar-scene-review-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @('AvatarAsset', 'AvatarRig', 'AvatarGlbLoader', 'AvatarDeformer', 'AvatarFraming',
    'AvatarGeometryBounds', 'BlendshapeSchema', 'AvatarPoseWorker', 'AvatarGpuScene','AvatarPbrShaderVariant','ResourceCleanup','AvatarOrmUploadPolicy', 'AvatarDrawPartition', 'AvatarBatchLayout', 'AvatarBatchGpu', 'AvatarPoseProgressWatchdog', 'RuntimeStatusOrder') |
    ForEach-Object { Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java" }
$sources += @('AvatarRigTest', 'AvatarGpuSceneProgressReviewTest', 'AvatarGpuSceneMetricsReviewTest', 'AvatarGpuSceneStopReviewTest', 'AvatarPoseProgressWatchdogTest', 'RuntimeStatusOrderTest', 'AvatarBatchShaderTest') |
    ForEach-Object { Join-Path $projectRoot "tests\$_.java" }
$sources += Join-Path $projectRoot 'app\src\main\java\com\mirror\bench\SceneViewSettings.java'
# JSON-java must precede android.jar: SDK JSON classes are nonfunctional host stubs.
# All production classes under review are compiled from current source; no APK or old Gradle classes.
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$JsonJar;$androidJar" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Scene review host compilation failed.' }
$classpath = "$classes;$JsonJar;$androidJar"
foreach ($test in @('AvatarGpuSceneProgressReviewTest', 'AvatarGpuSceneMetricsReviewTest', 'AvatarGpuSceneStopReviewTest', 'AvatarPoseProgressWatchdogTest', 'RuntimeStatusOrderTest', 'AvatarBatchShaderTest')) {
    & $java -cp $classpath "com.mirror.bench.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
}
Write-Host 'Scene CPU-logic review passed. Test-only Unsafe bypasses EGL construction; no GL/ADB/native calls ran.'
Write-Host 'This suite does not prove device rendering, Android lifecycle behavior, or presentation performance.'
