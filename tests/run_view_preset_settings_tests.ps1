param([string]$JavaHome='C:\Program Files\Java\jdk-17',[string]$AndroidJar='C:\Users\lNessie\AppData\Local\Android\Sdk\platforms\android-35\android.jar')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root 'app\build\view-preset-settings-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@('MirrorSettings','PanelCalibration','CameraControlSettings')|ForEach-Object{Join-Path $root "app\src\main\java\com\mirror\bench\$_.java"}
$sources+=@('ViewPresetSettingsTest','MirrorSettingsTest','CameraControlSettingsTest')|ForEach-Object{Join-Path $PSScriptRoot "$_.java"}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'View preset settings actual SDK compile failed'}
foreach($name in @('ViewPresetSettingsTest','MirrorSettingsTest','CameraControlSettingsTest')){
 & (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$AndroidJar" "com.mirror.bench.$name"
 if($LASTEXITCODE -ne 0){throw "$name failed"}
}
