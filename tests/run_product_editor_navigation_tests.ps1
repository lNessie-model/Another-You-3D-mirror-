param([string]$JavaHome='C:\Program Files\Java\jdk-17',
 [string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar',
 [string]$ClassesDirectory='')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
if(-not $ClassesDirectory){$ClassesDirectory=Join-Path $root 'app\build\product-editor-navigation-tests'}
$built=Join-Path $root 'app\build\intermediates\javac\debug\classes'
$json=Join-Path $root 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON dependency'}
New-Item -ItemType Directory -Force -Path $ClassesDirectory | Out-Null
$names=@('MirrorActivity','InterlaceRenderer','MirrorSettings','MirrorStartupGate','InteractionController')
$sources=$names|ForEach-Object{Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$json;$AndroidJar;$built" -d $ClassesDirectory @sources
if($LASTEXITCODE -ne 0){throw 'Editor actual SDK compilation failed'}
$tests=@('tests\editor-navigation-stubs\android\app\Activity.java',
 'tests\editor-navigation-stubs\android\content\Intent.java','tests\editor-navigation-stubs\com\mirror\bench\SceneViewPanel.java',
 'tests\editor-navigation-stubs\android\opengl\GLSurfaceView.java','tests\editor-navigation-stubs\android\os\Handler.java',
 'tests\editor-navigation-stubs\com\mirror\bench\CameraCalibrationPanel.java',
 'tests\persistent-fbo-stubs\android\os\Bundle.java','tests\stubs\android\os\SystemClock.java','tests\ProductEditorNavigationTest.java')|
 ForEach-Object{Join-Path $root $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$ClassesDirectory;$json;$AndroidJar;$built" -d $ClassesDirectory @tests
if($LASTEXITCODE -ne 0){throw 'Editor host compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$ClassesDirectory;$json;$AndroidJar;$built" com.mirror.bench.ProductEditorNavigationTest
if($LASTEXITCODE -ne 0){throw 'Editor navigation host checks failed'}
