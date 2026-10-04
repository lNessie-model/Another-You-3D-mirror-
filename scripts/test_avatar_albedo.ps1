param([string]$JavaHome='C:\Program Files\Java\jdk-17',
 [string]$AndroidSdk='C:\Users\lNessie\AppData\Local\Android\Sdk',
 [string]$Model='', [string]$Manifest='')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar=Join-Path $repo 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'JSON library differs'}
$run=Join-Path $repo ('app\build\albedo-current\run-'+[Guid]::NewGuid().ToString())
$classes=Join-Path $run 'classes';New-Item -ItemType Directory -Path $classes | Out-Null
$source=Join-Path $repo 'app\src\main\java\com\mirror\bench'
$names=@('AvatarAsset','AvatarGlbLoader','AvatarBatchLayout','AvatarRig','AvatarDeformer','BlendshapeSchema')
$inputs=@($names|ForEach-Object{Join-Path $source ($_+'.java')})
$inputs+=@('AvatarGlbLoaderTest','AvatarAlbedoAtlasTest','AvatarPbrAtlasTest','AvatarSmoothNormalsTest','AvatarDeformerTest','AvatarBatchLayoutTest','TripoHeadAssetCheck')|ForEach-Object{Join-Path $repo ('tests\'+$_+'.java')}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $jar -d $classes @inputs
if($LASTEXITCODE -ne 0){throw 'Current CPU profile compilation failed'}
$cp="$classes;$jar"
foreach($name in @('AvatarGlbLoaderTest','AvatarAlbedoAtlasTest','AvatarPbrAtlasTest','AvatarSmoothNormalsTest','AvatarDeformerTest','AvatarBatchLayoutTest')){
 & (Join-Path $JavaHome 'bin\java.exe') -cp $cp "com.mirror.bench.$name" | Tee-Object -FilePath (Join-Path $run ($name+'.log'))
 if($LASTEXITCODE -ne 0){throw "$name failed"}
}
if($Model){
 $arguments=@($Model);if($Manifest){$arguments+= $Manifest}
 & (Join-Path $JavaHome 'bin\java.exe') -cp $cp com.mirror.bench.TripoHeadAssetCheck @arguments | Tee-Object -FilePath (Join-Path $run 'actual-head.json')
 if($LASTEXITCODE -ne 0){throw 'Actual generated head rejected'}
}
& (Join-Path $repo 'tests\run_avatar_scene_review_tests.ps1') -JavaHome $JavaHome -AndroidSdk $AndroidSdk
$sceneClasses=Join-Path $repo 'app\build\avatar-scene-review-tests\classes'
$sdkJar=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$sceneClasses;$jar;$sdkJar" -d $sceneClasses (Join-Path $repo 'tests\AvatarAlbedoShaderTest.java')
if($LASTEXITCODE -ne 0){throw 'Current atlas shader compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$sceneClasses;$jar;$sdkJar" com.mirror.bench.AvatarAlbedoShaderTest | Tee-Object -FilePath (Join-Path $run 'shader.log')
if($LASTEXITCODE -ne 0){throw 'Current shader contract failed'}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$sceneClasses;$jar;$sdkJar" -d $sceneClasses (Join-Path $repo 'tests\AvatarPbrShaderTest.java')
if($LASTEXITCODE -ne 0){throw 'PBR shader contract compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$sceneClasses;$jar;$sdkJar" com.mirror.bench.AvatarPbrShaderTest | Tee-Object -FilePath (Join-Path $run 'pbr-shader.log')
if($LASTEXITCODE -ne 0){throw 'PBR shader contract failed'}
Write-Output "Current-source albedo evidence: $run; no device GL execution or ADB"
