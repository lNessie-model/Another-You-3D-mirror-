param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\npu-face-pipeline-stop-tests'
$built=Join-Path $root 'app\build\intermediates\javac\debug\classes'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('NpuFacePipeline','RknnModel','NativeCleanupUnconfirmed','ResourceCleanup','RuntimeInputStop','RuntimeStatusOrder')|ForEach-Object{Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$json;$AndroidJar;$built" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'NPU stop actual SDK compile failed'}
$tests=@('tests\npu-pipeline-stop-stubs\com\mirror\bench\RknnModel.java','tests\NpuFacePipelineStopTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$classes;$json;$AndroidJar;$built" -d $classes @tests
if($LASTEXITCODE -ne 0){throw 'NPU stop host fixture compile failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json;$AndroidJar;$built" com.mirror.bench.NpuFacePipelineStopTest
if($LASTEXITCODE -ne 0){throw 'NPU stop checks failed'}
$factory=@('tests\npu-factory-stubs\android\content\Context.java',
 'tests\npu-factory-stubs\android\content\pm\ApplicationInfo.java',
 'tests\npu-factory-stubs\android\content\res\AssetManager.java',
 'tests\NpuFaceFactoryFailureTest.java')|ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$classes;$json;$AndroidJar;$built" -d $classes @factory
if($LASTEXITCODE -ne 0){throw 'NPU constructor fixture compile failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$json;$AndroidJar;$built" com.mirror.bench.NpuFaceFactoryFailureTest (Join-Path $classes 'factory-files')
if($LASTEXITCODE -ne 0){throw 'NPU constructor checks failed'}
