param([string]$JavaHome='C:\Program Files\Java\jdk-17',
 [string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar',
 [string]$CompiledApp='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $CompiledApp){$CompiledApp=Join-Path $projectRoot 'app\build\intermediates\javac\debug\classes'}
if(-not(Test-Path -LiteralPath $AndroidJar)){throw 'Actual Android SDK jar required.'}
if(-not(Test-Path -LiteralPath (Join-Path $CompiledApp 'com\mirror\bench\MirrorTheme.class'))){throw 'Existing compiled app dependencies required; this runner does not start Gradle.'}
$run=Join-Path $projectRoot ('app\build\asset-library-lifecycle-tests\runs\'+[guid]::NewGuid().ToString())
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@('app\src\main\java\com\mirror\bench\AssetLibraryCatalog.java',
 'app\src\main\java\com\mirror\bench\AssetLibraryActivity.java','tests\AssetLibraryLifecycleTest.java') | ForEach-Object {Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$AndroidJar;$CompiledApp" -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw 'Asset library lifecycle Android compilation failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$AndroidJar;$CompiledApp" com.mirror.bench.AssetLibraryLifecycleTest 2>&1 | Tee-Object -FilePath (Join-Path $run 'lifecycle.log')
if($LASTEXITCODE -ne 0){throw 'Real Activity preference-boundary tests failed.'}
Write-Output "Asset library lifecycle PASS; evidence: $run"
