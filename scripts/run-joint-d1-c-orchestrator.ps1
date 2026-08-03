# Joint D1+C orchestrator - TOOLING ONLY (no production code change)
# Protocol: start while meeting STABLE, then flap.
#
# Lesson 200519: CREATE after OFFER_SENT/LOCAL_ACCEPT is too late -
#   same attempt already has ICE_RESTART_DISPATCHED(intentId=NONE) -> already_issued -> no HELD.
# Stop tweaking CREATED/FENCE waits; preempt production restart ownership.
#
# Valid exercise:
#   ATTEMPT_REQUESTED/WAKEUP before DISPATCHED(NONE)
#   -> CREATE -> CREATED+FENCE -> BLOCK
#   -> hard gate DISPATCHED(NONE,attemptA)=0
#   -> NEG -> HELD(DISPATCH)
#
# LOCAL_ACCEPT(L*) = D1 correlation only (field: DISPATCHED precedes LOCAL_ACCEPT).

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$Adb = "C:\adb\adb.exe",
    [string]$AuthSerial = "2d73067a",
    [string]$RemoteSerial = "MDX0220416001963",
    [string]$RemoteModule = "M03",
    [string]$AuthorityModule = "M02",
    [int]$AnchorTimeoutSec = 180,
    [int]$HeldTimeoutSec = 30,
    [int]$ExecutedTimeoutSec = 60,
    # Phase-3B harness: RELEASE immediately on HELD (no post-HELD NEG/sleep pacing).
    [switch]$ReleaseImmediatelyAfterHeld
)

$ErrorActionPreference = "Continue"
if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path "C:\adb\tmp" | Out-Null
$phaseLog = Join-Path $LogDir "phase-ops.txt"
$dumpDir = Join-Path $LogDir "adb-dumps"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
$authStream = Join-Path $LogDir "auth-stream.log"
$remStream = Join-Path $LogDir "rem-stream.log"

function Log([string]$m) {
    $line = "$(Get-Date -Format 'HH:mm:ss.fff') $m"
    Add-Content -Path $phaseLog -Value $line -Encoding UTF8
    Write-Host $line
}

function Invoke-AdbCmd([string]$argumentString) {
    $stamp = Get-Date -Format "HHmmssfff"
    $outFile = "C:\adb\tmp\o-$stamp-$PID.txt"
    $errFile = "C:\adb\tmp\e-$stamp-$PID.txt"
    try {
        $null = Start-Process -FilePath $Adb -ArgumentList $argumentString -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        $text = ""
        if (Test-Path $outFile) { $text = [IO.File]::ReadAllText($outFile) }
        if (Test-Path $errFile) { $text += [IO.File]::ReadAllText($errFile) }
        return $text
    } catch {
        Log "adb_fail args=$argumentString err=$_"
        return ""
    } finally {
        Remove-Item $outFile, $errFile -ErrorAction SilentlyContinue
    }
}

function BAuth([string]$action) {
    $s = "-s $AuthSerial shell am broadcast -p com.talkback.appprod -a $action --es remote $RemoteModule"
    $o = (Invoke-AdbCmd $s).Trim()
    Log "AUTH_BCAST $action :: $o"
}

function BRem([string]$action) {
    $s = "-s $RemoteSerial shell am broadcast -p com.talkback.appprod -a $action"
    $o = (Invoke-AdbCmd $s).Trim()
    Log "REM_BCAST $action :: $o"
}

function Dump-Auth([string]$tag) {
    $f = Join-Path $dumpDir ("auth-" + $tag + "-" + (Get-Date -Format "HHmmssfff") + ".log")
    $null = Start-Process -FilePath $Adb -ArgumentList "-s $AuthSerial logcat -d -v threadtime" `
        -NoNewWindow -Wait -RedirectStandardOutput $f -RedirectStandardError ($f + ".err")
    return $f
}

function Search-Dump([string]$dumpPath, [string]$pattern) {
    if (-not (Test-Path $dumpPath)) { return @() }
    return @(Select-String -Path $dumpPath -Pattern $pattern | ForEach-Object { $_.Line })
}

function Read-NewLines([string]$path, [ref]$offset) {
    if (-not (Test-Path $path)) { return @() }
    $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        if ($offset.Value -gt $fs.Length) { $offset.Value = 0 }
        $fs.Seek($offset.Value, [IO.SeekOrigin]::Begin) | Out-Null
        $sr = New-Object IO.StreamReader($fs, [Text.Encoding]::UTF8, $true, 4096, $true)
        try {
            $chunk = $sr.ReadToEnd()
            $offset.Value = $fs.Position
        } finally { $sr.Dispose() }
    } finally { $fs.Dispose() }
    if ([string]::IsNullOrEmpty($chunk)) { return @() }
    return @($chunk -split "`r?`n" | Where-Object { $_ })
}

function Find-ProdDispatchedNone([string[]]$lines, [string]$attempt) {
    return @($lines | Where-Object {
            $_ -match "RECOVERY_ICE_RESTART_DISPATCHED" -and
            $_ -match "remote=$RemoteModule" -and
            $_ -match "intentId=NONE" -and
            ( (-not $attempt) -or ($_ -match "attempt=$attempt\b") )
        })
}

function Find-PreemptAnchor([string[]]$lines) {
    # Prefer ATTEMPT_REQUESTED (seconds of headroom; adb cannot win ~20-40ms WAKEUP->DISPATCHED).
    # Exclude DEBUG_CREATE_* leftovers (Phase-3C attempt-1: probe R7 false-preempted without flap).
    return @($lines | Where-Object {
            $_ -match "Talkback:" -and
            $_ -notmatch "DEBUG_CREATE_DEFERRED_INTENT" -and
            $_ -notmatch "trigger=DEBUG_CREATE" -and
            (
                ($_ -match "RECOVERY_ATTEMPT_STATE" -and $_ -match "remote=$RemoteModule" -and $_ -match "to=ATTEMPT_REQUESTED") -or
                ($_ -match "RECOVERY_ATTEMPT_OBSERVATION" -and $_ -match "remote=$RemoteModule" -and $_ -match "attemptState=ATTEMPT_REQUESTED") -or
                ($_ -match "RECOVERY_WAKEUP_FIRED" -and $_ -match "edge=$RemoteModule") -or
                ($_ -match "RECOVERY_MEDIA_OWNER_ASSIGNED" -and $_ -match "remote=$RemoteModule") -or
                ($_ -match "RECOVERY_MEDIA_ACTION_DEFERRED" -and $_ -match "remote=$RemoteModule" -and $_ -match "disposition=DEFERRED")
            ) -and
            ($_ -match "attempt=\d+" -or $_ -match "attemptId=\d+")
        })
}

function Find-LocalAcceptL([string[]]$lines) {
    return @($lines | Where-Object {
            $_ -match "Talkback:" -and
            $_ -notmatch "RECOVERY_REATTACH_ACCEPTED" -and
            $_ -notmatch "ICE_MONITOR" -and
            $_ -match "remote=$RemoteModule" -and
            $_ -match "offerLineageId=L[0-9A-Za-z_\-]+" -and
            ($_ -match "stage=LOCAL_ACCEPT" -or $_ -match "stage=SEND_REQUEST" -or $_ -match "RECOVERY_OFFER_SENT")
        })
}

function Find-D1Drop([string[]]$lines, [string]$lineage) {
    return @($lines | Where-Object {
            $_ -match "D1_DEBUG_DROP_RECOVERY_INGRESS" -and
            $_ -match "from=$AuthorityModule" -and
            ( (-not $lineage) -or ($_ -match "offerLineageId=$lineage") )
        })
}

function Get-AttemptFromLine([string]$line) {
    $am = [regex]::Match($line, 'attempt=(\d+)')
    if (-not $am.Success) { $am = [regex]::Match($line, 'attemptId=(\d+)') }
    if ($am.Success) { return $am.Groups[1].Value }
    return $null
}

function Abort-InvalidExercise([string]$reason, [string]$detail) {
    Log "ABORT_INVALID_EXERCISE reason=$reason"
    Log $detail
    Log "classification=ABORT_PROD_RESTART_OWNED - not a J-B product FAIL; pr53Unlock stays BLOCKED"
    exit 7
}

Remove-Item $authStream, $remStream -ErrorAction SilentlyContinue
# Clear ring buffers so WATCH cannot match pre-run CREATE-probe leftovers as flap preempt.
$null = Invoke-AdbCmd "-s $AuthSerial logcat -c"
$null = Invoke-AdbCmd "-s $RemoteSerial logcat -c"
Start-Sleep -Milliseconds 300
$authProc = Start-Process -FilePath $Adb `
    -ArgumentList "-s $AuthSerial logcat -v threadtime Talkback:I *:S" `
    -NoNewWindow -PassThru -RedirectStandardOutput $authStream -RedirectStandardError ($authStream + ".err")
$remProc = Start-Process -FilePath $Adb `
    -ArgumentList "-s $RemoteSerial logcat -v threadtime Talkback:I *:S" `
    -NoNewWindow -PassThru -RedirectStandardOutput $remStream -RedirectStandardError ($remStream + ".err")

try {
    Log "ORCH start logDir=$LogDir"
    Log "WATCH baseline: auth+remote logcat -c (ignore pre-WATCH DEBUG_CREATE leftovers)"
    Log "targetEdge: from=$AuthorityModule to=$RemoteModule"
    Log "CREATE trigger: ATTEMPT_REQUESTED/WAKEUP/OWNER before DISPATCHED(NONE)"
    Log "NEG hard gate: CREATED+FENCE + DISPATCHED(NONE,attemptA)=0 else ABORT"
    Log "LOCAL_ACCEPT(L*): D1 correlation only (not CREATE trigger)"
    Log "C order: CREATE -> CREATED+FENCE -> BLOCK -> [gate] -> NEG -> HELD"
    Log "PROTOCOL: flap AFTER WATCH armed (WiFi OFF 20-30s)"

    BAuth "com.talkback.appprod.debug.PR52C_RELEASE_DISPATCH"

    $deadline = (Get-Date).AddSeconds($AnchorTimeoutSec)
    $authOff = [int64]0
    $remOff = [int64]0
    $attemptA = $null
    $offerL = $null
    $created = $false
    $intentId = $null
    $fenceArmed = $false
    $heldDispatch = $false
    $heldObservedAt = $null
    $heldToReleaseMs = $null
    $createSent = $false
    $preemptLine = $null

    Log "WATCH armed (live logcat) - flap now"

    while ((Get-Date) -lt $deadline -and -not $createSent) {
        $authNew = Read-NewLines $authStream ([ref]$authOff)
        $null = Read-NewLines $remStream ([ref]$remOff)

        $la = Find-LocalAcceptL $authNew
        if ($la.Count -ge 1 -and -not $offerL) {
            $m = [regex]::Match(($la | Select-Object -Last 1), 'offerLineageId=(L[^\s]+)')
            if ($m.Success) {
                $offerL = $m.Groups[1].Value
                Log "NOTE L*=$offerL seen (D1 correlation)"
            }
        }

        $anchors = Find-PreemptAnchor $authNew
        if ($anchors.Count -ge 1) {
            $preemptLine = ($anchors | Select-Object -Last 1)
            $attemptA = Get-AttemptFromLine $preemptLine

            $disp = @()
            if (Test-Path $authStream) {
                $disp = @(Select-String -Path $authStream -Pattern "RECOVERY_ICE_RESTART_DISPATCHED" -ErrorAction SilentlyContinue |
                        ForEach-Object { $_.Line } |
                        Where-Object {
                            $_ -match "remote=$RemoteModule" -and
                            $_ -match "intentId=NONE" -and
                            ( (-not $attemptA) -or ($_ -match "attempt=$attemptA\b") )
                        })
            }
            if ($disp.Count -ge 1) {
                Abort-InvalidExercise "PROD_DISPATCHED_BEFORE_CREATE" (
                    "attempt=$attemptA already ICE_RESTART_DISPATCHED(intentId=NONE) before C CREATE`n" +
                    "PREEMPT " + $preemptLine.Trim() + "`n" +
                    "DISPATCHED " + ($disp | Select-Object -Last 1).Trim()
                )
            }

            Log "PREEMPT_WINDOW attempt=$attemptA - CREATE before production DISPATCHED(NONE)"
            Log ("PREEMPT_ANCHOR " + $preemptLine.Trim())
            BAuth "com.talkback.appprod.debug.PR52C_CREATE"
            $createSent = $true
            break
        }

        $orphanDisp = Find-ProdDispatchedNone $authNew $null
        if ($orphanDisp.Count -ge 1 -and -not $createSent) {
            $attemptA = Get-AttemptFromLine ($orphanDisp | Select-Object -Last 1)
            if (-not $attemptA) { $attemptA = "?" }
            Abort-InvalidExercise "PROD_DISPATCHED_WITHOUT_PREEMPT" (
                "saw ICE_RESTART_DISPATCHED(intentId=NONE) attempt=$attemptA before CREATE`n" +
                "DISPATCHED " + ($orphanDisp | Select-Object -Last 1).Trim()
            )
        }

        Start-Sleep -Milliseconds 20
    }

    if (-not $createSent) {
        Log "TIMEOUT: no preempt window - STOP"
        Log "classification=TEST_HARNESS_PREEMPT_TIMEOUT"
        exit 2
    }

    $createDeadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $createDeadline -and -not ($created -and $fenceArmed)) {
        $authNew = Read-NewLines $authStream ([ref]$authOff)
        if (-not $created) {
            $cLines = @($authNew | Where-Object { $_ -match "DEFERRED_INTENT_CREATED" -and $_ -match "remote=$RemoteModule" })
            if ($cLines.Count -eq 0) {
                $dump = Dump-Auth "create"
                $cLines = @(Search-Dump $dump "DEFERRED_INTENT_CREATED" | Where-Object { $_ -match "remote=$RemoteModule" })
            }
            if ($cLines.Count -ge 1) {
                $created = $true
                $im = [regex]::Match(($cLines | Select-Object -Last 1), 'intentId=([^\s]+)')
                $intentId = if ($im.Success) { $im.Groups[1].Value } else { "?" }
                $a2 = Get-AttemptFromLine ($cLines | Select-Object -Last 1)
                if ($a2) { $attemptA = $a2 }
                Log "HIT DEFERRED_INTENT_CREATED intentId=$intentId attempt=$attemptA"
                Log ("CREATED " + ($cLines | Select-Object -Last 1).Trim())
            }
        }
        if ($created -and -not $fenceArmed) {
            $fenceLines = @($authNew | Where-Object {
                    $_ -match "DEFERRED_INTENT_VALIDATION_FENCE_ARMED" -and
                    $_ -match "remote=$RemoteModule" -and
                    ( (-not $intentId) -or ($_ -match "intentId=$intentId") )
                })
            if ($fenceLines.Count -eq 0) {
                $dump = Dump-Auth "fence"
                $fenceLines = @(Search-Dump $dump "DEFERRED_INTENT_VALIDATION_FENCE_ARMED" | Where-Object {
                        $_ -match "remote=$RemoteModule" -and ( (-not $intentId) -or ($_ -match "intentId=$intentId") )
                    })
            }
            if ($fenceLines.Count -ge 1) {
                $fenceArmed = $true
                Log "HIT VALIDATION_FENCE_ARMED intentId=$intentId"
                Log ("FENCE " + ($fenceLines | Select-Object -Last 1).Trim())
            }
        }

        $midDisp = @()
        if (Test-Path $authStream) {
            $midDisp = @(Select-String -Path $authStream -Pattern "RECOVERY_ICE_RESTART_DISPATCHED" -ErrorAction SilentlyContinue |
                    ForEach-Object { $_.Line } |
                    Where-Object {
                        $_ -match "remote=$RemoteModule" -and
                        $_ -match "intentId=NONE" -and
                        ( (-not $attemptA) -or ($_ -match "attempt=$attemptA\b") )
                    })
        }
        if ($midDisp.Count -ge 1) {
            Abort-InvalidExercise "PROD_DISPATCHED_BEFORE_HELD_GATE" (
                "attempt=$attemptA ICE_RESTART_DISPATCHED(intentId=NONE) while waiting CREATED/FENCE`n" +
                "DISPATCHED " + ($midDisp | Select-Object -Last 1).Trim()
            )
        }

        if (-not ($created -and $fenceArmed)) { Start-Sleep -Milliseconds 100 }
    }

    if (-not $created) { Log "FAIL: no CREATED - STOP"; exit 3 }
    if (-not $fenceArmed) { Log "FAIL: CREATED but FENCE missing - STOP"; exit 3 }

    # Phase-3C: win Pre-HELD race — NEG immediately after BLOCK (no Dump-Auth delay).
    # attempt-2b: EDGE_STARTED@+3s SUPERSEDED R10 before NEG landed HELD.
    Log "BLOCK+NEG immediate after CREATED+FENCE (intentId=$intentId attempt=$attemptA)"
    $gateDisp = @()
    if (Test-Path $authStream) {
        $gateDisp = @(Select-String -Path $authStream -Pattern "RECOVERY_ICE_RESTART_DISPATCHED" -ErrorAction SilentlyContinue |
                ForEach-Object { $_.Line } |
                Where-Object {
                    $_ -match "remote=$RemoteModule" -and
                    $_ -match "intentId=NONE" -and
                    ( (-not $attemptA) -or ($_ -match "attempt=$attemptA\b") )
                })
    }
    Log "NEG_GATE created=$created fence=$fenceArmed attempt=$attemptA prodDispatchedNone=$($gateDisp.Count)"
    if ($gateDisp.Count -ge 1) {
        Abort-InvalidExercise "NEG_GATE_PROD_DISPATCHED_NONE" (
            "must_hold DISPATCHED(NONE,attempt=$attemptA)=0 failed - abort before NEG`n" +
            "DISPATCHED " + ($gateDisp | Select-Object -Last 1).Trim()
        )
    }
    BAuth "com.talkback.appprod.debug.PR52C_BLOCK_DISPATCH"
    BAuth "com.talkback.appprod.debug.PR52C_NEG_EXECUTE"
    Log "NEG_EXECUTE fired immediately (stream-only gate; no dump delay)"

    $heldDeadline = (Get-Date).AddSeconds($HeldTimeoutSec)
    while ((Get-Date) -lt $heldDeadline -and -not $heldDispatch) {
        $authNew = Read-NewLines $authStream ([ref]$authOff)
        # HELD first. Bucket B only if SUPERSEDE while still CREATED (not HELD_DISPATCH).
        # attempt-3: R11 reached HELD_DISPATCH then EDGE SUPERSEDE — false Bucket B abort.
        $heldLines = @($authNew | Where-Object {
                $_ -match "DEFERRED_INTENT_HELD" -and
                $_ -match "remote=$RemoteModule" -and
                $_ -match "hold=DISPATCH" -and
                ( (-not $intentId) -or ($_ -match "intentId=$intentId") )
            })
        if ($heldLines.Count -eq 0) {
            $preHeldCut = @($authNew | Where-Object {
                    ($_ -match "DEFERRED_INTENT_SUPERSEDED" -and $_ -match "oldIntent=$intentId" -and $_ -match "oldState=CREATED") -or
                    ($_ -match "DEFERRED_INTENT_SUPERSEDED" -and $_ -match "oldIntent=$intentId" -and $_ -notmatch "oldState=HELD")
                })
            if ($preHeldCut.Count -ge 1) {
                Abort-InvalidExercise "BUCKET_B_PRE_HELD_SUPERSEDE" (
                    "Pre-HELD Stability Gate: SUPERSEDE before HELD`n" +
                    ($preHeldCut | Select-Object -Last 1).Trim()
                )
            }
        }
        if ($heldLines.Count -eq 0) {
            $dump = Dump-Auth "held"
            $heldLines = @(Search-Dump $dump "DEFERRED_INTENT_HELD" | Where-Object {
                    $_ -match "remote=$RemoteModule" -and $_ -match "hold=DISPATCH" -and (
                        (-not $intentId) -or ($_ -match "intentId=$intentId")
                    )
                })
        }
        if ($heldLines.Count -ge 1) {
            $heldDispatch = $true
            $heldObservedAt = Get-Date
            Log "HIT HELD(DISPATCH)"
            Log ("HELD " + ($heldLines | Select-Object -Last 1).Trim())
            break
        }
        $stale = @($authNew | Where-Object { $_ -match "STALE_DISCARD|DEFERRED_INTENT_REJECTED" -and $_ -match "intentId=$intentId" })
        if ($stale.Count -ge 1) {
            Log "FAIL_TIMING STALE before HELD (unexpected after NEG gate PASS)"
            exit 4
        }
        Start-Sleep -Milliseconds 200
    }

    if (-not $heldDispatch) { Log "FAIL_TIMING: no HELD(DISPATCH)"; exit 5 }

    Log "AUDIT: HELD reached; checking prod DISPATCHED(NONE) absence through HELD"
    $postHeldDisp = @()
    if (Test-Path $authStream) {
        $postHeldDisp = @(Select-String -Path $authStream -Pattern "RECOVERY_ICE_RESTART_DISPATCHED" -ErrorAction SilentlyContinue |
                ForEach-Object { $_.Line } |
                Where-Object {
                    $_ -match "remote=$RemoteModule" -and
                    $_ -match "intentId=NONE" -and
                    ( (-not $attemptA) -or ($_ -match "attempt=$attemptA\b") )
                })
    }
    if ($postHeldDisp.Count -ge 1) {
        Log "WARN: prod DISPATCHED(NONE) also present; HELD already observed - continue"
    } else {
        Log "AUDIT: prod DISPATCHED(NONE,attempt=$attemptA)=0 through HELD"
    }

    $gotExec = $false
    $heldSupersededPostRelease = $false
    if ($ReleaseImmediatelyAfterHeld) {
        # Phase-3B harness: pacing repair — RELEASE on HELD without post-HELD NEG or fixed sleep.
        Log "MODE=ReleaseImmediatelyAfterHeld (Phase-3B harness; J-B pacing repair)"
        if (-not $heldObservedAt) { $heldObservedAt = Get-Date }
        Log "HELD observed; immediate RELEASE_DISPATCH (intentId=$intentId) heldObservedAt=$($heldObservedAt.ToString('o'))"
        BAuth "com.talkback.appprod.debug.PR52C_RELEASE_DISPATCH"
        $releaseFiredAt = Get-Date
        $heldToReleaseMs = [int](($releaseFiredAt - $heldObservedAt).TotalMilliseconds)
        Log "J-B timing: HELD->RELEASE_DISPATCH ${heldToReleaseMs}ms (target <500ms)"

        $exDeadline = (Get-Date).AddSeconds($ExecutedTimeoutSec)
        while ((Get-Date) -lt $exDeadline) {
            $dump = Dump-Auth "terminal"
            $gotExec = (@(Search-Dump $dump "DEFERRED_INTENT_EXECUTED" | Where-Object {
                        $_ -match "intentId=$intentId"
                    })).Count -ge 1
            $heldSupersededPostRelease = (@(Search-Dump $dump "DEFERRED_INTENT_SUPERSEDED" | Where-Object {
                        $_ -match "oldIntent=$intentId" -and $_ -match "oldState=HELD_DISPATCH"
                    })).Count -ge 1
            Log "close exec=$gotExec superseded=$heldSupersededPostRelease intentId=$intentId"
            if ($gotExec) { Log "HIT EXECUTED intentId=$intentId"; break }
            if ($heldSupersededPostRelease) {
                Log "OBSERVE SUPERSEDED post-HELD (terminal; RELEASE may NOOP)"
                break
            }
            Start-Sleep -Milliseconds 500
        }

        Log "CLEAR D1 DROP (after J-B RELEASE attempt; secondary)"
        BRem "com.talkback.appprod.debug.D1_CLEAR_INGRESS_MISS"
        $d1Deadline = (Get-Date).AddSeconds(30)
        $d1 = @{ a = $false; e = $false; m = $false; r = $false; drop = $false }
        while ((Get-Date) -lt $d1Deadline) {
            $authNew = Read-NewLines $authStream ([ref]$authOff)
            $remNew = Read-NewLines $remStream ([ref]$remOff)
            if (-not $offerL) {
                $la = Find-LocalAcceptL $authNew
                if ($la.Count -ge 1) {
                    $m = [regex]::Match(($la | Select-Object -Last 1), 'offerLineageId=(L[^\s]+)')
                    if ($m.Success) { $offerL = $m.Groups[1].Value; Log "HIT L*=$offerL" }
                }
            }
            if ($offerL) {
                $drops = Find-D1Drop $remNew $offerL
                if ($drops.Count -ge 1) { $d1.drop = $true }
            }
            $dump = Dump-Auth "d1"
            $all = @(Get-Content -Path $dump -Encoding UTF8 -ErrorAction SilentlyContinue)
            if ($offerL) {
                $d1.a = (@($all | Where-Object { $_ -match "RECOVERY_REMOTE_INGRESS_ABSENT" -and $_ -match "to=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
                $d1.e = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_RETRY_EVALUATE" -and $_ -match "edge=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
                $d1.m = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_RETRY_ADMITTED" -and $_ -match "to=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
                $d1.r = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_RETRY_PENDING" -and $_ -match "to=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
            }
            Log "D1 L=$offerL drop=$($d1.drop) A=$($d1.a) E=$($d1.e) M=$($d1.m) R=$($d1.r)"
            if ($offerL -and $d1.a -and $d1.e -and $d1.m -and $d1.r) { break }
            Start-Sleep -Seconds 2
        }
    } else {
        $d1Deadline = (Get-Date).AddSeconds(45)
        $d1 = @{ a = $false; e = $false; m = $false; r = $false; drop = $false }
        while ((Get-Date) -lt $d1Deadline) {
            $authNew = Read-NewLines $authStream ([ref]$authOff)
            $remNew = Read-NewLines $remStream ([ref]$remOff)
            if (-not $offerL) {
                $la = Find-LocalAcceptL $authNew
                if ($la.Count -ge 1) {
                    $m = [regex]::Match(($la | Select-Object -Last 1), 'offerLineageId=(L[^\s]+)')
                    if ($m.Success) { $offerL = $m.Groups[1].Value; Log "HIT L*=$offerL" }
                }
            }
            if ($offerL) {
                $drops = Find-D1Drop $remNew $offerL
                if ($drops.Count -ge 1) { $d1.drop = $true }
            }
            $dump = Dump-Auth "d1"
            $all = @(Get-Content -Path $dump -Encoding UTF8 -ErrorAction SilentlyContinue)
            if ($offerL) {
                $d1.a = (@($all | Where-Object { $_ -match "RECOVERY_REMOTE_INGRESS_ABSENT" -and $_ -match "to=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
                $d1.e = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_RETRY_EVALUATE" -and $_ -match "edge=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
                $d1.m = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_RETRY_ADMITTED" -and $_ -match "to=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
                $d1.r = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_RETRY_PENDING" -and $_ -match "to=$RemoteModule" -and $_ -match "offerLineageId=$offerL" })).Count -ge 1
            }
            Log "D1 L=$offerL drop=$($d1.drop) A=$($d1.a) E=$($d1.e) M=$($d1.m) R=$($d1.r)"
            if ($offerL -and $d1.a -and $d1.e -and $d1.m -and $d1.r) { break }
            Start-Sleep -Seconds 2
        }

        Log "CLEAR D1 DROP (after HELD + D1 wait)"
        BRem "com.talkback.appprod.debug.D1_CLEAR_INGRESS_MISS"
        Start-Sleep -Seconds 5
        Log "NEG then RELEASE"
        BAuth "com.talkback.appprod.debug.PR52C_NEG_EXECUTE"
        Start-Sleep -Milliseconds 400
        BAuth "com.talkback.appprod.debug.PR52C_RELEASE_DISPATCH"

        $exDeadline = (Get-Date).AddSeconds($ExecutedTimeoutSec)
        while ((Get-Date) -lt $exDeadline) {
            $dump = Dump-Auth "exec"
            $gotExec = (@(Search-Dump $dump "DEFERRED_INTENT_EXECUTED" | Where-Object {
                        $_ -match "intentId=$intentId"
                    })).Count -ge 1
            Log "close exec=$gotExec"
            if ($gotExec) { Log "HIT EXECUTED"; break }
            Start-Sleep -Seconds 3
        }
    }

    Log "DONE offerL=$offerL intent=$intentId attempt=$attemptA held=$heldDispatch heldToReleaseMs=$heldToReleaseMs exec=$gotExec superseded=$heldSupersededPostRelease releaseImmediate=$ReleaseImmediatelyAfterHeld"
    if (-not $gotExec) { exit 6 }
    exit 0
}
finally {
    if ($authProc -and -not $authProc.HasExited) { Stop-Process -Id $authProc.Id -Force -ErrorAction SilentlyContinue }
    if ($remProc -and -not $remProc.HasExited) { Stop-Process -Id $remProc.Id -Force -ErrorAction SilentlyContinue }
}