param([string]$JavaHome='C:\Program Files\Java\jdk-17',
      [string]$AndroidSdk='C:\Users\lNessie\AppData\Local\Android\Sdk',
      [string]$EvidenceDirectory='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'JSON dependency differs'}
$sdkJar=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if(!$EvidenceDirectory){$EvidenceDirectory=Join-Path $projectRoot ('app\build\avatar-screen-bounds-tests\runs\'+[Guid]::NewGuid().ToString())}
if(Test-Path -LiteralPath $EvidenceDirectory){throw 'Screen bounds evidence directory must be new'}
$classes=Join-Path $EvidenceDirectory 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$names=@('AvatarAsset','AvatarGlbLoader','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarScreenBounds')
$sources=@($names|ForEach-Object{Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"})
# Compile production APIs against the real SDK before using the host JSON implementation.
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $sdkJar -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'sdk-compile.log')
if($LASTEXITCODE -ne 0){throw 'Screen bounds SDK compilation failed'}
$sources+=@('tests\AvatarRigTest.java','tests\AvatarScreenBoundsTest.java')|ForEach-Object{Join-Path $projectRoot $_}
$hashes=@($sources|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$hashes|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'sources.json') -Encoding utf8
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$jar;$sdkJar" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'host-compile.log')
if($LASTEXITCODE -ne 0){throw 'Screen bounds host compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -Xmx768m -cp "$classes;$jar;$sdkJar" com.mirror.bench.AvatarScreenBoundsTest (Join-Path $projectRoot 'app\src\main\assets') 2>&1 | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'checks.log')
if($LASTEXITCODE -ne 0){throw 'Actual uploaded screen bounds checks failed'}
Write-Output "Evidence: $EvidenceDirectory"
