param([Parameter(Mandatory=$true)][string]$ModelPath,[Parameter(Mandatory=$true)][string]$ApkPath,[Parameter(Mandatory=$true)][string]$OutputDirectory,
 [string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$build=Join-Path $root 'app\build\rknn-expression-java-tests'
$sdk=Join-Path $build 'sdk35';$hostClasses=Join-Path $build 'host'
New-Item -ItemType Directory -Force -Path $sdk,$hostClasses | Out-Null
$source=Join-Path $root 'app\src\main\java\com\mirror\bench\RknnExpression.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $sdk $source
if($LASTEXITCODE -ne 0){throw 'Expression Java SDK35 compilation failed.'}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $hostClasses $source (Join-Path $PSScriptRoot 'RknnExpressionTest.java')
if($LASTEXITCODE -ne 0){throw 'Expression Java host compilation failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $hostClasses com.mirror.bench.RknnExpressionTest $ModelPath $ApkPath $OutputDirectory
if($LASTEXITCODE -ne 0){throw 'Expression Java lifecycle tests failed.'}
