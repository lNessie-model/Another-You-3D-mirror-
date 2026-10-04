param([string]$JavaHome = 'C:\Program Files\Java\jdk-17', [string[]]$AssetPath = @())
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$json=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'
if ((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Expected real JSON-java fixture dependency; see avatar-loader.md.' }
$classes=Join-Path $projectRoot 'app\build\avatar-framing-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('app\src\main\java\com\mirror\bench\AvatarAsset.java','app\src\main\java\com\mirror\bench\AvatarGlbLoader.java','app\src\main\java\com\mirror\bench\AvatarFraming.java',
    'app\src\main\java\com\mirror\bench\AvatarGeometryBounds.java','app\src\main\java\com\mirror\bench\AvatarRig.java',
    'app\src\main\java\com\mirror\bench\AvatarDeformer.java','app\src\main\java\com\mirror\bench\BlendshapeSchema.java',
    'tests\AvatarRigTest.java','tests\AvatarFramingTest.java','tests\AvatarGeometryBoundsTest.java') | ForEach-Object {Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $json -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Framing test compilation failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json" com.mirror.bench.AvatarFramingTest @AssetPath
if($LASTEXITCODE -ne 0){throw 'Framing tests failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json" com.mirror.bench.AvatarGeometryBoundsTest @AssetPath
if($LASTEXITCODE -ne 0){throw 'Automatic geometry bounds tests failed.'}
