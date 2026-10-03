param(
    [string]$AndroidSdk = '',
    [string]$JavaHome = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) {
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $AndroidSdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
}
if (-not $AndroidSdk) { throw 'Pass -AndroidSdk or configure sdk.dir in local.properties (Android SDK 35 required).' }
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Android SDK 35 jar missing: $androidJar" }

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
} else {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $java = (Get-Command java -ErrorAction Stop).Source
}
foreach ($tool in @($javac, $java)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Java 17 compiler/runtime missing: $tool" }
}

# Link the existing Android Activity dependency without replacing its percentile helper.
# Every class under test below is recompiled from source; no old renderer/controller code is executed.
$androidClasses = Join-Path $projectRoot 'app\build\intermediates\javac\debug\classes'
if (-not (Test-Path -LiteralPath (Join-Path $androidClasses 'com\mirror\bench\BenchActivity.class'))) {
    throw 'Build assembleDebug once before running host renderer tests; BenchActivity.class is a compile/link dependency.'
}
$testClasses = Join-Path $projectRoot 'app\build\java-regression-tests'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
$sources = @(
    'tests\stubs\android\os\SystemClock.java',
    'app\src\main\java\com\mirror\bench\YuvPacking.java',
    'app\src\main\java\com\mirror\bench\ResourceCleanup.java',
    'app\src\main\java\com\mirror\bench\FaceFrame.java',
    'app\src\main\java\com\mirror\bench\FaceControlCalibration.java',
    'app\src\main\java\com\mirror\bench\FaceControlMapper.java',
    'app\src\main\java\com\mirror\bench\InteractionController.java',
    'app\src\main\java\com\mirror\bench\RuntimeProgressWatchdog.java',
    'app\src\main\java\com\mirror\bench\AvatarPoseProgressWatchdog.java',
    'app\src\main\java\com\mirror\bench\MultiviewGl.java',
    'app\src\main\java\com\mirror\bench\PersistentMultiviewFbos.java',
    'app\src\main\java\com\mirror\bench\PersistentMultiviewGl.java',
    'app\src\main\java\com\mirror\bench\AvatarCameraProjectionCache.java',
    'app\src\main\java\com\mirror\bench\PanelCalibration.java',
    'app\src\main\java\com\mirror\bench\PanelTestImages.java',
    'app\src\main\java\com\mirror\bench\MirrorSettings.java',
    'app\src\main\java\com\mirror\bench\CameraControlSettings.java',
    'app\src\main\java\com\mirror\bench\FramePacingStats.java',
    'app\src\main\java\com\mirror\bench\ViewSubmissionTiming.java',
    'app\src\main\java\com\mirror\bench\InterlaceRenderer.java',
    'tests\YuvPackingTest.java',
    'tests\ResourceCleanupTest.java',
    'tests\InteractionControllerTest.java',
    'tests\RuntimeProgressWatchdogTest.java',
    'tests\AvatarPoseProgressWatchdogTest.java',
    'tests\RendererRuntimeTest.java',
    'tests\RendererPacingTest.java',
    'tests\PanelCalibrationTest.java',
    'tests\PanelPhaseContractionTest.java',
    'tests\PanelTestImagesTest.java',
    'tests\PanelRendererConfigTest.java',
    'tests\MirrorSettingsTest.java'
) | ForEach-Object { Join-Path $projectRoot $_ }
& $javac --release 17 -encoding UTF-8 -cp "$androidJar;$androidClasses" -d $testClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'Host regression test compilation failed.' }

$classpath = "$testClasses;$androidJar;$androidClasses"
foreach ($test in @('YuvPackingTest', 'ResourceCleanupTest', 'InteractionControllerTest', 'RuntimeProgressWatchdogTest', 'AvatarPoseProgressWatchdogTest', 'RendererRuntimeTest', 'RendererPacingTest', 'PanelCalibrationTest', 'PanelPhaseContractionTest', 'PanelTestImagesTest', 'PanelRendererConfigTest', 'MirrorSettingsTest')) {
    & $java -cp $classpath "com.mirror.bench.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
}
Write-Host 'Host regressions passed. Android lifecycle, native inference and GPU rendering still require device checks.'
