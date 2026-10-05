param([string]$JsonJar='', [string]$JavaHome='C:\Program Files\Java\jdk-17', [string]$CatalogPath='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $JsonJar){$JsonJar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'}
if(-not(Test-Path -LiteralPath $JsonJar)){throw 'Real JSON-java20240303 jar required; see tests/avatar-loader.md.'}
if((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON-java jar SHA256.'}
$run=Join-Path $projectRoot ('app\build\bundled-catalog-tests\runs\'+[guid]::NewGuid().ToString())
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@('tests\bundled-stubs\android\content\Context.java','tests\bundled-stubs\android\content\SharedPreferences.java','tests\bundled-stubs\android\content\res\AssetManager.java',
 'app\src\main\java\com\mirror\bench\BundledAvatarCatalog.java','app\src\main\java\com\mirror\bench\BundledAvatarSelection.java',
 'tests\BundledCatalogTestSupport.java','tests\BundledAvatarCatalogTest.java','tests\BundledAvatarSelectionTest.java') | ForEach-Object {Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $JsonJar -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw 'Bundled catalog host compilation failed.'}
$actual=@();if($CatalogPath){$actual=@($CatalogPath)}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$JsonJar" com.mirror.bench.BundledAvatarCatalogTest @actual 2>&1 | Tee-Object -FilePath (Join-Path $run 'catalog.log')
if($LASTEXITCODE -ne 0){throw 'Bundled catalog tests failed.'}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$JsonJar" com.mirror.bench.BundledAvatarSelectionTest 2>&1 | Tee-Object -FilePath (Join-Path $run 'selection.log')
if($LASTEXITCODE -ne 0){throw 'Bundled selection tests failed.'}
Write-Output "Bundled catalog and selection PASS; evidence: $run"
