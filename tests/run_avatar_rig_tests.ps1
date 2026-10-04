param([string]$JsonJar = '', [string]$JavaHome = '', [string[]]$AssetPath = @())
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JsonJar) { $JsonJar = Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar' }
if (-not (Test-Path -LiteralPath $JsonJar)) { throw 'Real JSON-java 20240303 jar required; see tests/avatar-loader.md.' }
if ((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Unexpected JSON-java SHA256.' }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) { $javac=Join-Path $JavaHome 'bin\javac.exe'; $java=Join-Path $JavaHome 'bin\java.exe' }
else { $javac=(Get-Command javac -ErrorAction Stop).Source; $java=(Get-Command java -ErrorAction Stop).Source }
$classes=Join-Path $projectRoot 'app\build\avatar-rig-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@(
 'app\src\main\java\com\mirror\bench\AvatarAsset.java',
 'app\src\main\java\com\mirror\bench\AvatarRig.java',
 'app\src\main\java\com\mirror\bench\AvatarGlbLoader.java',
 'app\src\main\java\com\mirror\bench\AvatarDeformer.java',
 'app\src\main\java\com\mirror\bench\AvatarFraming.java',
 'app\src\main\java\com\mirror\bench\AvatarGeometryBounds.java',
 'app\src\main\java\com\mirror\bench\BlendshapeSchema.java',
 'app\src\main\java\com\mirror\bench\InteractionController.java',
 'app\src\main\java\com\mirror\bench\FaceControlCalibration.java',
 'app\src\main\java\com\mirror\bench\FaceControlMapper.java',
 'app\src\main\java\com\mirror\bench\FaceFrame.java',
 'tests\AvatarRigTest.java', 'tests\AvatarRigReviewTest.java', 'tests\AvatarRigV2Test.java',
 'tests\BlendshapeSchemaTest.java', 'tests\InteractionControllerTest.java'
) | ForEach-Object {Join-Path $projectRoot $_}
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $JsonJar -d $classes @sources
if ($LASTEXITCODE -ne 0) {throw 'Avatar rig review compilation failed.'}
foreach ($test in @('AvatarRigTest','AvatarRigReviewTest','BlendshapeSchemaTest','InteractionControllerTest')) {
 & $java -cp "$classes;$JsonJar" "com.mirror.bench.$test"
 if ($LASTEXITCODE -ne 0) {throw "$test failed."}
}
& $java -cp "$classes;$JsonJar" com.mirror.bench.AvatarRigV2Test @AssetPath
if ($LASTEXITCODE -ne 0) {throw 'AvatarRigV2Test failed.'}
Write-Host 'Rig/schema/controller host tests passed; GPU and authored-expression acceptance remain separate.'
