param([string]$JavaHome='C:/Program Files/Java/jdk-17',[string]$AndroidJar='C:/Users/lNessie/AppData/Local/Android/Sdk/platforms/android-35/android.jar')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$built=Join-Path $root 'app/build/intermediates/javac/debug/classes'
$out=Join-Path $root 'app/build/pbr-check-contract-tests'
$json=Join-Path $root 'app/build/avatar-tests/json-20240303.jar'
if((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'){throw 'Unexpected JSON-java test dependency'}
New-Item -ItemType Directory -Force -Path $out | Out-Null
[string[]]$sources=@('tests/AvatarPbrFastMathCheckContractTest.java') | ForEach-Object {Join-Path $root $_}
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp "$built;$json;$AndroidJar" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'PBR image gate contract test compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$out;$built;$json;$AndroidJar" com.mirror.bench.AvatarPbrFastMathCheckContractTest
if($LASTEXITCODE -ne 0){throw 'PBR image gate contract config tests failed'}
