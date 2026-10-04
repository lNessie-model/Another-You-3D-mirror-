param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root 'app\build\rknn-model-lifecycle-tests'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources=@('RknnModel','NativeCleanupUnconfirmed')|ForEach-Object{Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=Join-Path $root 'tests\RknnModelLifecycleTest.java'
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$json;$AndroidJar" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Model lifecycle SDK compile failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$out;$json;$AndroidJar" com.mirror.bench.RknnModelLifecycleTest
if($LASTEXITCODE -ne 0){throw 'Model lifecycle checks failed'}
