param([string]$MsvcRoot='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207',[string]$WindowsSdk='C:\Program Files (x86)\Windows Kits\10',[string]$SdkVersion='10.0.26100.0',[string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root 'app\build\rknn-face-cleanup-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$fixture=Join-Path $out 'model.bin';[IO.File]::WriteAllBytes($fixture,[byte[]](0..15))
& (Join-Path $MsvcRoot 'bin\Hostx64\x64\cl.exe') /nologo /std:c11 /experimental:c11atomics /W4 /WX /D_CRT_SECURE_NO_WARNINGS `
 /I (Join-Path $MsvcRoot 'include') /I (Join-Path $WindowsSdk "Include\$SdkVersion\ucrt") `
 /I (Join-Path $JavaHome 'include') /I (Join-Path $JavaHome 'include\win32') `
 /I (Join-Path $PSScriptRoot 'face-jni-stubs') /I (Join-Path $PSScriptRoot 'expression-jni-stubs') /I (Join-Path $root 'native\vendor\rknn') `
 "/Fo$out\cleanup.obj" "/Fe$out\cleanup.exe" (Join-Path $PSScriptRoot 'rknn_face_cleanup_test.c') /link `
 "/LIBPATH:$MsvcRoot\lib\x64" "/LIBPATH:$WindowsSdk\Lib\$SdkVersion\ucrt\x64" "/LIBPATH:$WindowsSdk\Lib\$SdkVersion\um\x64"
if($LASTEXITCODE -ne 0){throw 'Face JNI cleanup harness compile failed'}
foreach($mode in @(0,1,2,3,4,5)){
 & (Join-Path $out 'cleanup.exe') $mode $fixture
 if($LASTEXITCODE -ne 0){throw "Face JNI cleanup mode $mode failed"}
}
