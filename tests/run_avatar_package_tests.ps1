param([string]$JsonJar = '', [string]$JavaHome = '', [string]$AssetPath = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JsonJar) { $JsonJar = Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar' }
if (-not (Test-Path -LiteralPath $JsonJar)) { throw 'Real JSON-java 20240303 jar required; see tests/avatar-loader.md.' }
if ((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Unexpected JSON-java SHA256.' }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) { $javac=Join-Path $JavaHome 'bin\javac.exe'; $java=Join-Path $JavaHome 'bin\java.exe' }
else { $javac=(Get-Command javac -ErrorAction Stop).Source; $java=(Get-Command java -ErrorAction Stop).Source }
$classes=Join-Path $projectRoot 'app\build\avatar-package-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('AvatarAsset','AvatarGlbLoader','AvatarRig','AvatarDeformer','AvatarFraming','AvatarGeometryBounds','BlendshapeSchema','AvatarPackageStore') | ForEach-Object {Join-Path $projectRoot "app\src\main\java\com\mirror\bench\$_.java"}
$sources += Join-Path $PSScriptRoot 'AvatarPackageStoreTest.java'
$sources += Join-Path $PSScriptRoot 'stubs\android\os\StatFs.java'
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $JsonJar -d $classes @sources
if ($LASTEXITCODE -ne 0) {throw 'Avatar package compilation failed.'}
$arguments=@((Join-Path $projectRoot 'app\build\avatar-package-tests\runs'))
if ($AssetPath) { $arguments += $AssetPath }
& $java -Xmx512m -cp "$classes;$JsonJar" com.mirror.bench.AvatarPackageStoreTest @arguments
if ($LASTEXITCODE -ne 0) {throw 'Avatar package tests failed.'}
