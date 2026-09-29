param(
    [string]$AdbPath = 'C:\Users\lNessie\platform-tools-latest-windows\platform-tools\adb.exe',
    [string]$JournalPath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $JournalPath) {
    $besideScript = Join-Path $PSScriptRoot 'cleanup-journal.json'
    $JournalPath = if (Test-Path -LiteralPath $besideScript) { $besideScript } else { 'E:\tripo\output\device-benchmark\20260929\cleanup-journal.json' }
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB executable was not found. Supply -AdbPath.' }
$journal = Get-Content -LiteralPath $JournalPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($journal.serial -ne '6L32552009566714') { throw 'Journal belongs to a different device.' }
function Invoke-DeviceAdb([string[]]$Arguments) {
    $reply = & $AdbPath -s $journal.serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($reply -join "`n") }
    return ($reply -join "`n")
}
$serial = Invoke-DeviceAdb @('get-serialno')
if ($serial.Trim() -ne $journal.serial) { throw 'Device serial does not match.' }
$factoryPackage = $journal.previous_home.Split('/')[0]
if ($journal.previous_home -match 'ResolverActivity' -or $factoryPackage -ne 'com.softwinner.launcher') {
    throw 'Original launcher is not verified in this journal.'
}
$commands = @{ 0='default-state'; 1='enable'; 2='disable'; 3='disable-user'; 4='disable-until-used' }
foreach ($entry in $journal.packages) {
    if (-not $entry.applied -and -not $entry.attempted) { continue }
    if ($entry.package -notmatch '^[a-zA-Z][a-zA-Z0-9_.]+$' -or -not $commands.ContainsKey([int]$entry.original_enabled)) {
        throw 'Invalid package state in journal.'
    }
    Invoke-DeviceAdb @('shell','pm',$commands[[int]$entry.original_enabled],'--user','0',$entry.package)
}
Invoke-DeviceAdb @('shell','cmd','package','set-home-activity','--user','0',$factoryPackage)
$resolved = Invoke-DeviceAdb @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
if ($resolved -notmatch 'com.softwinner.launcher/') { throw 'Factory launcher did not become HOME.' }
Invoke-DeviceAdb @('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
Write-Output 'Factory applications and HOME restored; firmware and app data were preserved.'
