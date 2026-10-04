param([Parameter(Mandatory=$true)][string]$OutputDirectory,
 [string]$NdkRoot='E:\tripo\native-tools\android-ndk-r25c')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$clang=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$binary=Join-Path $OutputDirectory 'rknn_blendshape_probe'
if(Test-Path -LiteralPath $binary){throw 'Refuse to replace an existing isolated helper; choose a new output directory.'}
& $clang --target=aarch64-linux-android30 -O2 -Wall -Wextra -Werror -fPIE -pie `
 -I (Join-Path $projectRoot 'native\vendor\rknn') (Join-Path $projectRoot 'native\rknn_blendshape_probe.c') `
 -o $binary -ldl -lm
if($LASTEXITCODE){throw 'Isolated RKNN helper compilation failed.'}
$metadata=@{target='aarch64-linux-android30';ndk=$NdkRoot;source_sha256=(Get-FileHash (Join-Path $projectRoot 'native\rknn_blendshape_probe.c') -Algorithm SHA256).Hash.ToLowerInvariant();contract_sha256=(Get-FileHash (Join-Path $projectRoot 'native\rknn_blendshape_contract.h') -Algorithm SHA256).Hash.ToLowerInvariant();helper_sha256=(Get-FileHash $binary -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item $binary).Length;app_modified=$false;device_executed=$false}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'build.json') -Encoding utf8
$metadata | ConvertTo-Json
