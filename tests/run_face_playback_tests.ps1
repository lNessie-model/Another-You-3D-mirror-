param([string]$JavaHome='C:\Program Files\Java\jdk-17')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskClasses=Join-Path $taskRoot 'app\build\face-playback-tests'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSources=@('BlendshapeSchema','SceneViewSettings','FaceControlMapper','FaceControlCalibration') | ForEach-Object {Join-Path $taskRoot "app\src\main\java\com\mirror\bench\$_.java"}
$taskPlayback=Join-Path $taskRoot 'app\src\main\java\com\mirror\bench\FacePlayback.java'
if(Test-Path -LiteralPath $taskPlayback){$taskSources+=$taskPlayback}
$taskCandidate=Join-Path $taskRoot 'app\src\main\java\com\mirror\bench\FacePlaybackCandidate.java'
if(Test-Path -LiteralPath $taskCandidate){$taskSources+=$taskCandidate}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $taskClasses @taskSources (Join-Path $PSScriptRoot 'FacePlaybackTest.java') (Join-Path $PSScriptRoot 'FaceExpressionResponseTest.java')
if($LASTEXITCODE){throw 'Face playback tests compilation failed'}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $taskClasses @taskSources (Join-Path $PSScriptRoot 'FacePlaybackCandidateTest.java') (Join-Path $PSScriptRoot 'FacePlaybackCandidateMapperTest.java')
if($LASTEXITCODE){throw 'Face playback candidate test compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasses com.mirror.bench.FacePlaybackTest
if($LASTEXITCODE){throw 'Face playback requirements failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasses com.mirror.bench.FaceExpressionResponseTest
if($LASTEXITCODE){throw 'User expression response requirements failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasses com.mirror.bench.FacePlaybackCandidateTest
if($LASTEXITCODE){throw 'Eye-wide candidate requirements failed'}
& (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasses com.mirror.bench.FacePlaybackCandidateMapperTest
if($LASTEXITCODE){throw 'Calibrated candidate mapper requirements failed'}
