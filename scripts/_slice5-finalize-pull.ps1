$ErrorActionPreference = "Continue"
$adb = (Get-Command adb).Source
$LogDir = "D:\workspace\project\talkback\talkback\logs\slice5-mcast-three-20260904-151058"
$runId = "slice5-mcast-three-20260904-151058"
Write-Host "Waiting for M02/M04 adb to finish..."
for ($i = 0; $i -lt 40; $i++) {
    $alive = Get-Process -Id 33544, 32936 -ErrorAction SilentlyContinue
    if (-not $alive) {
        Write-Host "processes done"
        break
    }
    Start-Sleep -Seconds 2
}
Write-Host "=== M02 instrument ==="
Get-Content (Join-Path $LogDir "M02-instrument.out.txt") -Tail 20 -ErrorAction SilentlyContinue
Write-Host "=== M04 instrument ==="
Get-Content (Join-Path $LogDir "M04-instrument.out.txt") -Tail 20 -ErrorAction SilentlyContinue
& $adb devices -l

function Pull-One([string]$Name, [string]$Serial, [string]$RoleHint) {
    $st = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    Write-Host "$Name state=$st"
    if ($st -ne "device") { return $false }
    $localDir = Join-Path $LogDir "device-reports"
    foreach ($remote in @(
            "/sdcard/Android/data/com.talkback.test/files/slice5-mcast-three",
            "/sdcard/Android/data/com.talkback.appprod/files/slice5-mcast-three"
        )) {
        $tmp = Join-Path $localDir "_pull_${Name}_final"
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        $null = & $adb -s $Serial pull $remote $tmp 2>&1
        $match = Get-ChildItem $tmp -Recurse -Filter "$runId-*.json" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($match) {
            $role = if ($match.Name -match "-(sender|receiver)\.json$") { $Matches[1] } else { $RoleHint }
            Copy-Item $match.FullName (Join-Path $localDir "$Name-$role.json") -Force
            Write-Host "GOT $Name-$role"
            return $true
        }
    }
    return $false
}

Pull-One "M02" "DSJ-2407041" "sender"
Pull-One "M04" "DSJ-2407011" "receiver"

$m04 = Join-Path $LogDir "device-reports\M04-receiver.json"
if (Test-Path $m04) {
    $m = (Get-Content $m04 -Raw | ConvertFrom-Json).metrics
    Write-Host "gateAllPass=$($m.gateAllPass)"
    Write-Host "ingress S1=$($m.ingressAcceptedBySource.S1) S2=$($m.ingressAcceptedBySource.S2) S3=$($m.ingressAcceptedBySource.S3)"
    Write-Host "decode S1=$($m.decodeSuccessBySource.S1) S2=$($m.decodeSuccessBySource.S2) S3=$($m.decodeSuccessBySource.S3)"
    Write-Host "tripleMix=$($m.mixCyclesWithThreeParticipants) writes=$($m.successfulWrites) rejected=$($m.ingressRejected)"
    $verdict = if ($m.gateAllPass) { "PASS" } else { "FAIL" }
    Write-Host "VERDICT=$verdict"
} else {
    Write-Host "NEED_M04_USB_FOR_PULL"
}
