param([Parameter(Mandatory=$true)][string]$OutputDirectory,
 [string]$NdkRoot='E:\tripo\native-tools\android-ndk-r25c')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$clang=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$source=Join-Path $projectRoot 'native\rknn_normalized_blendshape_probe.c'
$baseline=Join-Path $projectRoot 'native\rknn_blendshape_probe.c'
$contract=Join-Path $projectRoot 'native\rknn_blendshape_contract.h'
$api=Join-Path $projectRoot 'native\vendor\rknn\rknn_api.h'
if(Test-Path -LiteralPath $OutputDirectory){throw 'Refuse to reuse an existing output directory.'}
if((Get-FileHash -LiteralPath $baseline -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'fbd596fca5678e3820cf153e994c9902e69a2407013c0f6bf1baa8ed9593d361'){throw 'Frozen baseline source changed.'}
$oldScope='isolated mixed CPU/NPU expression model; not full face pipeline or render FPS'
$newScope='CPU FP32 fixed-front normalized [1,146,2] input; isolated mixed CPU/NPU suffix; not full face pipeline or render FPS'
$oldOrder='original C-order [1,146,2], adjacent pixel x/y; no preprocessing or transpose'
$newOrder='CPU FP32 fixed-front normalized C-order [1,146,2], adjacent x/y; no preprocessing or transpose in helper'
$expected=[System.IO.File]::ReadAllText($baseline).Replace($oldScope,$newScope).Replace($oldOrder,$newOrder)
if([System.IO.File]::ReadAllText($source) -cne $expected){throw 'Normalized helper must differ by the two approved text literals only.'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$binary=Join-Path $OutputDirectory 'rknn_blendshape_probe'
$compileArgs=@('--target=aarch64-linux-android30','-O2','-Wall','-Wextra','-Werror','-fPIE','-pie',
 '-I',(Join-Path $projectRoot 'native\vendor\rknn'),$source,'-o',$binary,'-ldl','-lm')
& $clang @compileArgs
if($LASTEXITCODE){throw 'Normalized isolated helper compilation failed.'}
$metadata=@{target='aarch64-linux-android30';ndk=$NdkRoot;command=@($clang)+$compileArgs;
 source_sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant();
 baseline_source_sha256=(Get-FileHash -LiteralPath $baseline -Algorithm SHA256).Hash.ToLowerInvariant();
 contract_sha256=(Get-FileHash -LiteralPath $contract -Algorithm SHA256).Hash.ToLowerInvariant();
 api_header_sha256=(Get-FileHash -LiteralPath $api -Algorithm SHA256).Hash.ToLowerInvariant();
 clang_sha256=(Get-FileHash -LiteralPath $clang -Algorithm SHA256).Hash.ToLowerInvariant();
 helper_sha256=(Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant();
 build_script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();
 bytes=(Get-Item -LiteralPath $binary).Length;input_order=$newOrder;diagnostic_scope=$newScope;
 changes='Only diagnostic scope and input_order literals; original API/shape/iteration/release/timing unchanged';
 app_modified=$false;device_executed=$false}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'build.json') -Encoding utf8
$metadata | ConvertTo-Json -Depth 5
