param([string]$Jdk = 'C:\Program Files\Java\jdk-17')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskClasses = Join-Path ([System.IO.Path]::GetTempPath()) ('mirror-coverage-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskClasses | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $taskClasses (Join-Path $taskRoot 'app\src\main\java\com\mirror\bench\AvatarMaterialCoverageStats.java') (Join-Path $PSScriptRoot 'AvatarMaterialCoverageStatsTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Coverage statistics compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp $taskClasses com.mirror.bench.AvatarMaterialCoverageStatsTest
if ($LASTEXITCODE -ne 0) { throw 'Coverage statistics checks failed' }
