param(
 [Parameter(Mandatory=$true)][string]$Workfile,
 [Parameter(Mandatory=$true)][string]$AssetDirectory,
 [Parameter(Mandatory=$true)][string]$OutDirectory,
 [string]$Baseline='',
 [string]$JavaHome='C:\Program Files\Java\jdk-17',
 [string]$BlenderExe='C:\Program Files\Blender Foundation\Blender 4.5\blender.exe'
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$Workfile=[IO.Path]::GetFullPath($Workfile)
$AssetDirectory=[IO.Path]::GetFullPath($AssetDirectory)
$OutDirectory=[IO.Path]::GetFullPath($OutDirectory)
if(Test-Path -LiteralPath $OutDirectory){throw 'Preserve previous evidence; choose a new output directory'}
if(-not (Test-Path -LiteralPath $Workfile -PathType Leaf)){throw 'Workfile missing'}
$jar=Join-Path $repo 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'JSON library differs'}
$classes=Join-Path $OutDirectory 'classes';New-Item -ItemType Directory -Path $classes | Out-Null
$sourceDir=Join-Path $repo 'app\src\main\java\com\mirror\bench'
$sources=@('AvatarAsset','AvatarGlbLoader','AvatarRig','BlendshapeSchema','SceneViewSettings','FaceControlMapper','FaceControlCalibration','FacePlayback') | ForEach-Object {Join-Path $sourceDir ($_+'.java')}
$sources+=Join-Path $PSScriptRoot 'BlenderFaceControlReference.java'
$hashes=@($sources|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$hashes|ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutDirectory 'production-source-sha256.json') -Encoding utf8
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $jar -d $classes @sources
if($LASTEXITCODE){throw 'Production reference compilation failed'}
$reference=Join-Path $OutDirectory 'production-control-reference.json'
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$jar" com.mirror.bench.BlenderFaceControlReference $AssetDirectory $reference
if($LASTEXITCODE){throw 'Production reference export failed'}
$arguments=@('--reference',$reference,'--manual-edit','--report',(Join-Path $OutDirectory 'authoring-check.json'))
if($Baseline){$arguments+=@('--baseline',[IO.Path]::GetFullPath($Baseline))}
& $BlenderExe -b $Workfile --disable-autoexec --python-exit-code 1 --python (Join-Path $PSScriptRoot 'check_blender_face_controls.py') -- @arguments
if($LASTEXITCODE){throw 'Actual Blender authoring controls failed'}
Write-Output "Production reference and editable rig evidence: $OutDirectory"
