param([Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$NdkRoot='E:\tripo\native-tools\android-ndk-r25c')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$buildRoot=[System.IO.Path]::GetFullPath((Join-Path $root 'app\build'))+[System.IO.Path]::DirectorySeparatorChar
$destination=[System.IO.Path]::GetFullPath($OutputDirectory)
if(!$destination.StartsWith($buildRoot,[System.StringComparison]::OrdinalIgnoreCase)){throw 'Expression JNI output must be a new directory below app/build; never overwrite production jniLibs.'}
if(Test-Path -LiteralPath $destination){throw 'Expression JNI build output already exists.'}
$clang=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$api=Join-Path $root 'native\vendor\rknn\rknn_api.h'
if((Get-FileHash -LiteralPath $api -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'f280732314c2d9dae871faa84946efaa8477499579236474fe0c9ea8b018571e'){throw 'RKNN API header changed.'}
$sources=@('native\rknn_expression.c','native\rknn_expression_core.h','native\vendor\rknn\rknn_api.h','scripts\build_rknn_expression.ps1')
$before=@{};foreach($source in $sources){$before[$source]=(Get-FileHash -LiteralPath (Join-Path $root $source) -Algorithm SHA256).Hash.ToLowerInvariant()}
New-Item -ItemType Directory -Path $destination | Out-Null
$binary=Join-Path $destination 'libmirror_rknn_expression.so'
$arguments=@('--target=aarch64-linux-android30','-O2','-Wall','-Wextra','-Werror','-fPIC','-shared','-Wl,-z,defs','-Wl,-soname,libmirror_rknn_expression.so',
 '-I',(Join-Path $root 'native\vendor\rknn'),(Join-Path $root 'native\rknn_expression.c'),'-o',$binary,'-ldl','-lm')
& $clang @arguments
if($LASTEXITCODE -ne 0){throw 'Expression JNI build failed.'}
foreach($source in $sources){if((Get-FileHash -LiteralPath (Join-Path $root $source) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $before[$source]){throw 'Expression JNI source changed while building.'}}
$report=@{target='aarch64-linux-android30';ndk=$NdkRoot;ndk_properties=(Get-Content -LiteralPath (Join-Path $NdkRoot 'source.properties') -Raw);command=@($clang)+$arguments;
 sources=$before;clang_sha256=(Get-FileHash -LiteralPath $clang -Algorithm SHA256).Hash.ToLowerInvariant();
 binary_sha256=(Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $binary).Length;
 production_jniLibs_modified=$false;application_integrated=$false;device_executed=$false}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'build.json') -Encoding utf8
$report | ConvertTo-Json -Depth 6
