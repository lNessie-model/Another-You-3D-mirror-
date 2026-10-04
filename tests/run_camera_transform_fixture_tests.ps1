param(
    [string]$JavaHome='C:\Program Files\Java\jdk-17',
    [string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar'
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jsonJar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'
if ((Get-FileHash -LiteralPath $jsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Unexpected host JSON-java dependency' }
$sdkClasses=Join-Path $projectRoot 'app\build\camera-transform-fixture-tests\sdk-classes'
$hostClasses=Join-Path $projectRoot 'app\build\camera-transform-fixture-tests\host-classes'
New-Item -ItemType Directory -Force -Path $sdkClasses,$hostClasses | Out-Null
$sources=@('CameraInputTransform.java','CameraBitmapNormalizer.java','CameraTransformCheckActivity.java') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $sdkClasses @sources
if($LASTEXITCODE -ne 0){throw 'Real SDK API compilation failed'}
$sources=@('tests\bitmap-stubs\android\graphics\Bitmap.java','tests\CameraTransformFixtureTest.java') | ForEach-Object {Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$sdkClasses;$jsonJar;$AndroidJar" -d $hostClasses @sources
if($LASTEXITCODE -ne 0){throw 'Fixture host test compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$hostClasses;$sdkClasses;$jsonJar;$AndroidJar" com.mirror.bench.CameraTransformFixtureTest
if($LASTEXITCODE -ne 0){throw 'Fixture report checks failed'}
Write-Host 'SDK compilation and host report checks passed; run CameraTransformCheckActivity on Android for real Bitmap evidence.'
