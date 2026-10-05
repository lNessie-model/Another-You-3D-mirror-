param([string]$JsonJar='',[string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AssetsRoot='', [string]$Apk='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if($AssetsRoot -and $Apk){throw 'Choose actual assets directory or one built APK.'}
if(-not $JsonJar){$JsonJar=Join-Path $projectRoot 'app\build\avatar-tests\json-20240303.jar'}
if((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Pinned real JSON-java jar SHA differs.'}
$run=Join-Path $projectRoot ('app\build\asset-library-tests\runs\'+[guid]::NewGuid().ToString())
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@('tests\bundled-stubs\android\content\res\AssetManager.java','app\src\main\java\com\mirror\bench\BundledAvatarCatalog.java',
 'app\src\main\java\com\mirror\bench\AssetLibraryCatalog.java','tests\AssetLibraryCatalogTest.java') | ForEach-Object {Join-Path $projectRoot $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $JsonJar -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw 'Asset library host compilation failed.'}
$actual=@();if($AssetsRoot){$actual=@('--assets-root',$AssetsRoot)}elseif($Apk){$actual=@('--apk',$Apk)}
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$JsonJar" com.mirror.bench.AssetLibraryCatalogTest @actual 2>&1 | Tee-Object -FilePath (Join-Path $run 'catalog.log')
if($LASTEXITCODE -ne 0){throw 'Asset library catalog or actual byte checks failed.'}
Write-Output "Asset library PASS; evidence: $run"
