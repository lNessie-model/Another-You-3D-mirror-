param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar',[string]$JsonJar='')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
if(-not $JsonJar){$JsonJar=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'}
if(-not (Test-Path -LiteralPath $JsonJar)){throw 'Real JSON-java 20240303 required; no download/stub JSON runtime.'}
if((Get-FileHash -Algorithm SHA256 -LiteralPath $JsonJar).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON-java SHA256'}
$classes=Join-Path $root 'app\build\runtime-config-backup-codec-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('MirrorSettings','PanelCalibration','CameraControlSettings','RuntimeConfigBackupCodec')|ForEach-Object{Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
# Compile production against actual SDK declarations before the host JSON implementation.
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Configuration backup codec SDK35 compile failed'}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$classes;$JsonJar;$AndroidJar" -d $classes (Join-Path $PSScriptRoot 'RuntimeConfigBackupCodecTest.java')
if($LASTEXITCODE -ne 0){throw 'Configuration backup codec host test compile failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$JsonJar;$AndroidJar" com.mirror.bench.RuntimeConfigBackupCodecTest
if($LASTEXITCODE -ne 0){throw 'Configuration backup codec tests failed'}
