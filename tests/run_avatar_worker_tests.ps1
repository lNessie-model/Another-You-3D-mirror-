param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string[]]$AssetPath=@())
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Expected real JSON-java; see avatar-loader.md.'}
$classes=Join-Path $root 'app\build\avatar-worker-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('AvatarAsset','AvatarGlbLoader','AvatarDeformer','AvatarRig','AvatarPoseWorker','BlendshapeSchema') | ForEach-Object {Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=@('AvatarRigTest','AvatarPoseWorkerTest','AvatarPoseWorkerStopTest') | ForEach-Object {Join-Path $root "tests\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $json -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Worker test compilation failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json" com.mirror.bench.AvatarPoseWorkerTest @AssetPath
if($LASTEXITCODE -ne 0){throw 'Worker tests failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json" com.mirror.bench.AvatarPoseWorkerStopTest
if($LASTEXITCODE -ne 0){throw 'Worker stop checks failed.'}
