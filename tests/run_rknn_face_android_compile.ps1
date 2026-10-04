param([string]$NdkRoot='E:\tripo\native-tools\android-ndk-r25c')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$bin=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$out=Join-Path $root 'app\build\rknn-face-android-compile\arm64-v8a'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$library=Join-Path $out 'libmirror_rknn_face.so'
& (Join-Path $bin 'clang.exe') --target=aarch64-linux-android30 -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared `
 -I (Join-Path $root 'native\vendor\rknn') (Join-Path $root 'native\rknn_face.c') -o $library -ldl -lm -ljnigraphics
if($LASTEXITCODE -ne 0){throw 'Isolated face Android JNI compile failed'}
$header=& (Join-Path $bin 'llvm-readelf.exe') -h $library
if($LASTEXITCODE -ne 0 -or -not ($header -match 'AArch64')){throw 'Unexpected native architecture'}
$symbols=& (Join-Path $bin 'llvm-readelf.exe') --dyn-syms --wide $library
if($LASTEXITCODE -ne 0){throw 'Cannot inspect JNI symbols'}
foreach($name in @('openNative','infoNative','runNative','closeNative','cropNative','cropReferenceNative')){
 if(-not ($symbols -match "Java_com_mirror_bench_RknnModel_$name")){throw "Missing JNI entry $name"}
}
Write-Output ('Isolated Android arm64 JNI compile passed; 6 entry points; SHA256 '+(Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash)
Write-Output 'Output stays under app/build; packaged JNI libraries and APK are unchanged. No Android driver execution.'
