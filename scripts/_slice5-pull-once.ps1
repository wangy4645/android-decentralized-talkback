$ErrorActionPreference = "Continue"
$adb = (Get-Command adb).Source
$LogDir = "D:\workspace\project\talkback\talkback\logs\slice5-mcast-three-20260904-150535"
$runId = "slice5-mcast-three-20260904-150535"
$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "DSJ-2407041"
    M03 = "MDX0220416001963"
    M04 = "DSJ-2407011"
}
Write-Host "=== sleep 30s for probes ==="
Start-Sleep -Seconds 30
Write-Host "=== adb ==="
& $adb devices -l
$localDir = Join-Path $LogDir "device-reports"
New-Item -ItemType Directory -Force -Path $localDir | Out-Null
foreach ($name in @("M01", "M02", "M03", "M04")) {
    $s = $devices[$name]
    $st = try { (& $adb -s $s get-state 2>&1 | Out-String).Trim() } catch { "offline" }
    Write-Host "$name state=$st"
    if ($st -ne "device") { continue }
    foreach ($remote in @(
            "/sdcard/Android/data/com.talkback.test/files/slice5-mcast-three",
            "/sdcard/Android/data/com.talkback.appprod/files/slice5-mcast-three"
        )) {
        $tmp = Join-Path $localDir "_pull_$name"
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        $null = & $adb -s $s pull $remote $tmp 2>&1
        $match = Get-ChildItem $tmp -Recurse -Filter "$runId-*.json" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($match) {
            $role = if ($match.Name -match "-(sender|receiver)\.json$") { $Matches[1] } else { "unknown" }
            Copy-Item $match.FullName (Join-Path $localDir "$name-$role.json") -Force
            Write-Host "GOT $name $role $($match.Name)"
        }
    }
}
Write-Host "=== reports ==="
Get-ChildItem $localDir -Filter "*.json" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_.FullName }
Write-Host "=== instrument logs ==="
Get-ChildItem $LogDir -Filter "*-instrument.txt" -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ("{0} len={1}" -f $_.Name, $_.Length)
    Get-Content $_.FullName -Tail 8
    Write-Host "---"
}
