param([ValidateSet('All','Owner')][string]$Suite='All',
 [string]$JavaHome='C:\Program Files\Java\jdk-17',
 [string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar=Join-Path $repo 'app\build\avatar-tests\json-20240303.jar'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON library'}
$run=Join-Path $repo ('app\build\avatar-cache-current\run-'+[Guid]::NewGuid().ToString())
$classes=Join-Path $run 'sdk-classes';$hostClasses=Join-Path $run 'host-classes'
New-Item -ItemType Directory -Path $classes,$hostClasses | Out-Null
Write-Output "Current-source run: $run"
$source=Join-Path $repo 'app\src\main\java\com\mirror\bench'
$names=@('AvatarAsset','AvatarBatchGpu','AvatarBatchLayout','AvatarDeformer','AvatarFraming','AvatarGeometryBounds',
 'AvatarGlbLoader','AvatarGpuScene','AvatarDrawPartition','AvatarManagementGate','AvatarPoseProgressWatchdog','AvatarPoseWorker','AvatarRig',
 'BlendshapeSchema','AvatarPackageStore','AvatarManagementActivity')
$production=@($names|ForEach-Object{Join-Path $source ($_+'.java')})
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @production
if($LASTEXITCODE -ne 0){throw 'Current-source SDK compilation failed'}
$testNames=@('AvatarCacheTest','AvatarStoreOwnerTest','AvatarPackageStoreTest','AvatarManagementGateTest',
 'AvatarManagementDeadlineTest','AvatarManagementCatalogTest')
$tests=@($testNames|ForEach-Object{Join-Path $repo ('tests\'+$_+'.java')})
$tests+=Join-Path $repo 'tests\stubs\android\os\StatFs.java'
if(-not (Test-Path -LiteralPath $tests[-1])){throw 'Missing existing StatFs host boundary'}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp "$classes;$jar;$AndroidJar" -d $hostClasses @tests
if($LASTEXITCODE -ne 0){throw 'Current-source test compilation failed'}
$classpath="$hostClasses;$classes;$jar;$AndroidJar"
& (Join-Path $JavaHome 'bin\java.exe') -Xmx512m -cp $classpath com.mirror.bench.AvatarStoreOwnerTest (Join-Path $run 'owner')
if($LASTEXITCODE -ne 0){throw 'Current Manager owner regression failed'}
$bytecode=& (Join-Path $JavaHome 'bin\javap.exe') -p -c -classpath $classes com.mirror.bench.AvatarManagementActivity
if($LASTEXITCODE -ne 0){throw 'Manager bytecode inspection failed'}
$text=$bytecode -join "`n"
$destroy=[regex]::Match($text,'(?s)protected void onDestroy\(\);.*?(?=\n  (?:private|protected|public))').Value
$open=[regex]::Match($text,'(?s)private com\.mirror\.bench\.AvatarPackageStore openStore\(\).*?(?=\n  (?:private|protected|public))').Value
if($destroy -notmatch 'StoreOwner.close' -or $open -notmatch 'StoreOwner.get'){throw 'Missing current Manager get/close hookup'}
Write-Output 'Current Manager bytecode hookup: 2 checks GREEN, not Android lifecycle execution'
if($Suite -eq 'All'){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $fixture=Join-Path $run 'test-avatar.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $repo 'app\src\main\assets\avatars\builtin-guide'),$fixture)
    & (Join-Path $JavaHome 'bin\java.exe') -Xmx512m -cp $classpath com.mirror.bench.AvatarCacheTest (Join-Path $run 'cache') $fixture
    if($LASTEXITCODE -ne 0){throw 'Current Store cache regression failed'}
    & (Join-Path $JavaHome 'bin\java.exe') -Xmx512m -cp $classpath com.mirror.bench.AvatarPackageStoreTest (Join-Path $run 'legacy') (Join-Path $repo 'app\src\main\assets\avatars\builtin-guide\character.glb')
    if($LASTEXITCODE -ne 0){throw 'Current original Store regression failed'}
    foreach($name in @('AvatarManagementGateTest','AvatarManagementDeadlineTest','AvatarManagementCatalogTest')){
        & (Join-Path $JavaHome 'bin\java.exe') -Xmx512m -cp $classpath "com.mirror.bench.$name" (Join-Path $run 'management')
        if($LASTEXITCODE -ne 0){throw "$name regression failed"}
    }
}
Write-Output 'No ADB, APK build, install, production mutation or Android lifecycle execution.'
