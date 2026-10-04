param(
    [string]$JsonJar = '',
    [string]$JavaHome = '',
    [string[]]$AssetPath = @()
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JsonJar) { $JsonJar = Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar' }
if (-not (Test-Path -LiteralPath $JsonJar)) {
    throw 'Real JSON-java 20240303 jar required. See tests/avatar-loader.md; Android android.jar is a stub and is not a test runtime.'
}
$expected = '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'
if ((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne $expected) { throw 'Unexpected JSON-java jar SHA256.' }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
} else {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $java = (Get-Command java -ErrorAction Stop).Source
}
$classes = Join-Path $projectRoot 'app\build\avatar-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    'app\src\main\java\com\mirror\bench\AvatarAsset.java',
    'app\src\main\java\com\mirror\bench\AvatarGlbLoader.java',
    'app\src\main\java\com\mirror\bench\AvatarDeformer.java',
    'app\src\main\java\com\mirror\bench\AvatarBatchLayout.java',
    'tests\AvatarGlbLoaderTest.java',
    'tests\AvatarDeformerTest.java',
    'tests\AvatarDeformerReference.java',
    'tests\AvatarDeformerOptimizationTest.java',
    'tests\AvatarDeformerHeapCacheTest.java',
    'tests\AvatarBatchLayoutTest.java'
) | ForEach-Object { Join-Path $projectRoot $_ }
& $javac --release 17 -encoding UTF-8 -cp $JsonJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Avatar GLB test compilation failed.' }
& $java -cp "$classes;$JsonJar" com.mirror.bench.AvatarGlbLoaderTest @AssetPath
if ($LASTEXITCODE -ne 0) { throw 'Avatar GLB tests failed.' }
& $java -cp "$classes;$JsonJar" com.mirror.bench.AvatarDeformerTest @AssetPath
if ($LASTEXITCODE -ne 0) { throw 'Avatar deformation tests failed.' }
& $java -cp "$classes;$JsonJar" com.mirror.bench.AvatarDeformerOptimizationTest @AssetPath
if ($LASTEXITCODE -ne 0) { throw 'Avatar deformation optimization parity failed.' }
& $java -cp "$classes;$JsonJar" com.mirror.bench.AvatarDeformerHeapCacheTest @AssetPath
if ($LASTEXITCODE -ne 0) { throw 'Avatar deformation heap cache regression failed.' }
& $java -cp "$classes;$JsonJar" com.mirror.bench.AvatarBatchLayoutTest @AssetPath
if ($LASTEXITCODE -ne 0) { throw 'Avatar batch layout regression failed.' }
Write-Host 'CPU GLB parser tests passed with real JSON-java. Renderer/device/complete-avatar acceptance remains separate.'
