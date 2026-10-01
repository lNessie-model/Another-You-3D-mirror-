param(
    [string]$NdkRoot = 'E:\tripo\native-tools\android-ndk-r25c',
    [string]$RgaLibrary = 'E:\tripo\native-tools\librga-ndk.so'
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$clang=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$cppRuntime=Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'
$expectedRga='D0A7C20120601E010D5A84653D36D552156743F75DA7D64E2161B96B19FE602B'
if((Get-FileHash -LiteralPath $RgaLibrary -Algorithm SHA256).Hash -ne $expectedRga) {
    throw 'RGA library differs from the tested 1.10.6_[3] Android NDK build. Verify it before changing this pin.'
}
$destination=Join-Path $projectRoot 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force $destination | Out-Null
Copy-Item -LiteralPath $RgaLibrary -Destination (Join-Path $destination 'librga.so')
Copy-Item -LiteralPath $cppRuntime -Destination $destination
& $clang --target=aarch64-linux-android24 -O2 -Wall -Wextra -fPIC -shared `
    -I (Join-Path $projectRoot 'native\vendor\rga') (Join-Path $projectRoot 'native\rga_convert.c') `
    (Join-Path $destination 'librga.so') -ljnigraphics -llog -o (Join-Path $destination 'libmirror_accel.so')
if($LASTEXITCODE) { throw 'RGA JNI compilation failed' }
$probeDestination=Join-Path $projectRoot 'tools\rknn_probe'
New-Item -ItemType Directory -Force (Split-Path $probeDestination -Parent) | Out-Null
& $clang --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie `
    -I (Join-Path $projectRoot 'native\vendor\rknn') (Join-Path $projectRoot 'native\rknn_probe.c') -o $probeDestination -ldl
if($LASTEXITCODE) { throw 'RKNN probe compilation failed' }
& $clang --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIC -shared -DMIRROR_RKNN_JNI `
    -I (Join-Path $projectRoot 'native\vendor\rknn') (Join-Path $projectRoot 'native\rknn_probe.c') `
    -o (Join-Path $destination 'libmirror_npu.so') -ldl
if($LASTEXITCODE) { throw 'RKNN JNI compilation failed' }
& $clang --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIC -shared `
    -I (Join-Path $projectRoot 'native\vendor\rknn') (Join-Path $projectRoot 'native\rknn_face.c') `
    -o (Join-Path $destination 'libmirror_rknn_face.so') -ldl -lm -ljnigraphics
if($LASTEXITCODE) { throw 'Face RKNN JNI compilation failed' }
& $clang --target=aarch64-linux-android24 -O2 -Wall -Wextra -fPIC -shared `
    (Join-Path $projectRoot 'native\multiview_gl.c') -lEGL -lGLESv2 `
    -o (Join-Path $destination 'libmirror_gl.so')
if($LASTEXITCODE) { throw 'Multiview JNI compilation failed' }
Write-Output 'Native binaries built without modifying target system libraries.'
