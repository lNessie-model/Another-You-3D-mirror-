param([Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$NdkRoot='E:\tripo\native-tools\android-ndk-r25c',[ValidateSet('front','stem','ln','scale')][string]$Profile='front')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$clang=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$binary=Join-Path $OutputDirectory 'rknn_tap_probe'
if(Test-Path -LiteralPath $binary){throw 'Choose a new isolated helper directory; existing binaries are immutable.'}
$profileId=switch($Profile){'front'{0};'stem'{1};'ln'{2};'scale'{3}}
& $clang --target=aarch64-linux-android30 -O2 -Wall -Wextra -Werror -fPIE -pie "-DMIRROR_TAP_PROFILE=$profileId" `
 -I (Join-Path $projectRoot 'native\vendor\rknn') (Join-Path $projectRoot 'native\rknn_tap_probe.c') -o $binary -ldl -lm
if($LASTEXITCODE){throw 'Five tap helper compilation failed.'}
$sourceHashes=@{}
foreach($file in @('native\rknn_tap_probe.c','native\rknn_tap_contract.h','native\rknn_tap_iteration.h','native\vendor\rknn\rknn_api.h')){$sourceHashes[$file]=(Get-FileHash (Join-Path $projectRoot $file) -Algorithm SHA256).Hash.ToLowerInvariant()}
$metadata=@{target='aarch64-linux-android30';ndk=$NdkRoot;profile=$Profile;sources=$sourceHashes;helper_sha256=(Get-FileHash $binary -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item $binary).Length;app_modified=$false;device_executed=$false}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'build.json') -Encoding utf8
$metadata | ConvertTo-Json -Depth 4
