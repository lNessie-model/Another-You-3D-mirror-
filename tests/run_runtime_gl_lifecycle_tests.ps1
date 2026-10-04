param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\runtime-gl-lifecycle-tests'
$built=Join-Path $root 'app\build\intermediates\javac\debug\classes'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON-java test dependency'}
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$names=@('RuntimeGlLifecycle','RuntimeViewCount','RuntimeInputStop','NativeCleanupUnconfirmed','RuntimeStatusOrder','MirrorSettings','MirrorActivity','InterlaceRenderer')
$names+=@('AvatarBackgroundCache','AvatarBackgroundGl','AvatarGpuScene','AvatarPoseWorker','AvatarDrawPartition')
$sources=$names|ForEach-Object{Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$json;$AndroidJar;$built" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'GL lifecycle actual SDK compile failed'}
$tests=@('tests\persistent-fbo-stubs\android\os\Bundle.java','tests\stubs\android\os\SystemClock.java','tests\RuntimeGlLifecycleTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$classes;$json;$AndroidJar;$built" -d $classes @tests
if($LASTEXITCODE -ne 0){throw 'GL lifecycle host compile failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json;$AndroidJar;$built" com.mirror.bench.RuntimeGlLifecycleTest
if($LASTEXITCODE -ne 0){throw 'GL lifecycle host checks failed'}
