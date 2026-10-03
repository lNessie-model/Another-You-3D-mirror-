param([string]$MsvcRoot='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207', [string]$WindowsSdk='C:\Program Files (x86)\Windows Kits\10', [string]$SdkVersion='10.0.26100.0')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root 'app\build\rknn-expression-core-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& (Join-Path $MsvcRoot 'bin\Hostx64\x64\cl.exe') /nologo /std:c11 /W4 /WX /D_CRT_SECURE_NO_WARNINGS `
 /I (Join-Path $MsvcRoot 'include') /I (Join-Path $WindowsSdk "Include\$SdkVersion\ucrt") `
 /I (Join-Path $root 'native\vendor\rknn') "/Fo$out\core.obj" "/Fe$out\core.exe" `
 (Join-Path $PSScriptRoot 'rknn_expression_core_test.c') /link `
 "/LIBPATH:$MsvcRoot\lib\x64" "/LIBPATH:$WindowsSdk\Lib\$SdkVersion\ucrt\x64" "/LIBPATH:$WindowsSdk\Lib\$SdkVersion\um\x64"
if($LASTEXITCODE -ne 0){throw 'Expression core host test compilation failed.'}
& (Join-Path $out 'core.exe')
if($LASTEXITCODE -ne 0){throw 'Expression core fault-injection test failed.'}
