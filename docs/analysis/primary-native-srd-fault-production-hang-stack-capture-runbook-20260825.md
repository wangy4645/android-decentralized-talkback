# Primary Native SRD Fault — Production Hang Stack Capture Runbook

**ID:** primary-native-srd-fault-production-hang-stack-capture-runbook-20260825  
**Date:** 2026-08-25  
**Type:** **Read-only / capture-only** · **NO product change · NO field experiment matrix**  
**Status:** **AUTHORIZED** · **R3 Run Contract FROZEN** (2026-08-26)  
**Parent:** [primary-native-srd-fault-native-phase2-source-trace-20260825.md](./primary-native-srd-fault-native-phase2-source-trace-20260825.md) · [pinned instrumentation design](./primary-native-srd-fault-pinned-instrumentation-design-20260825.md) · [Track B CLOSED](./conference-mesh-live-presentation-contract-review-20260826.md)

---

## 1. Purpose

Lab closed without repro (2PC + 3PC PASS). **Next high-value step:** on the **next real production** host SRD hang, capture **native thread stacks** so S1/S2/S3/S7 move from desk hypothesis to **concrete wait site**.

**Runbook PASS** is **not** “we got a stack file.”

> **PASS:** adjudication can state which **native thread / lock / Invoke / task** the failing edge thread is blocked on.

---

## 2. Authorization boundary

### Authorized

```text
Observe hang in place
logcat capture (host + failing device if USB)
SIGQUIT stack dump (kill -3) — non-terminating
/proc thread listing + native/Java stack paste
Timeline export ±1–2s around SRD_NATIVE_CALL_ENTER
Write capture artifact under talkback/logs/
```

### Not authorized during capture

```text
force-release · PC reset · Factory partition · native reset
SRD retry · hangup to “recover” before stacks captured
pm clear · am force-stop · kill -9
lease manipulation · Implementation IA · Java re-prime / ordinal gate
Expand lab (4PC+) · new field contrast matrix
```

**Do not treat Java logs as a substitute for native stacks.**

---

## 3. Frozen context (do not re-litigate)

```text
Primary RCA (field 1a/1b)     ACCEPTED — ordinal #2 TIER_JNI typical
2PC / 3PC lab                 ALL PASS — no minimal-graph repro
Java re-prime / C+B           NOT SELECTED
Lab expansion                 STOP
Implementation IA             BLOCKED until stack names wait site
```

---

## 4. Prerequisites

**Superseded for R3 (inst capture):** use **§4a R3 Run Contract** below.  
**Legacy stack-only capture** (pre-`pnsrd-inst-1`): table in this section still applies if not running inst build.

| Item | Requirement |
|------|-------------|
| Build | Production/debug with **DIO-1** markers (see run card) |
| Package | `com.talkback.appprod` |
| Host log | **M01** `HTUBB21B09220661` USB `adb logcat` for full session |
| Topology | 4p group conference · M01 host/anchor |
| WebRTC | `stream-webrtc-android:1.3.10` — pin: [primary-native-srd-fault-webrtc-revision-pin-20260825.md](./primary-native-srd-fault-webrtc-revision-pin-20260825.md) |

---

## 4a. R3 Run Contract (FROZEN 2026-08-26)

**Purpose:** ONE valid production R3 capture to **close mutex owner graph** (Track A only).  
**Not authorized:** new architecture review · Java telemetry · lab experiments · Track B amendment.

### Frozen track posture

```text
Track B — MESH LIVE          CLOSED (Q1=NO · no amendment · no implementation)
Track A — Primary Native SRD ONLY remaining work
```

### Device roster (fixed)

| Device | Role | Requirement |
|--------|------|-------------|
| **M04** | — | **Powered off** (not in CH-01 admission set) |
| **M01** | Host + capture laptop USB | **inst APK** · `pnsrd-inst-1` · `HTUBB21B09220661` |
| **M02** | Peer | Online · `DSJ-2407041` · `com.talkback.appprod` |
| **M03** | Peer (typical failing edge) | Online · `MDX0220416001963` · `com.talkback.appprod` |

Topology: **3p MESH bootstrap conference** on field roster (M01+M02+M03) is **runtime-valid** and expected for this capture.

### Hard rule — UI non-authoritative (Track B sign-off)

```text
3p MESH CONNECTING UI  = contract-consistent  = non-authoritative
```

```text
UI CONNECTING / LIVE pill     MUST NOT drive field behavior on R3 runs

Authoritative adjudication:
  SRD_ENTER / SRD_EXIT / SRD_NATIVE_CALL_ENTER / SRD_NATIVE_CALL_EXIT
  ICE_CONNECTED / CONFERENCE_JOIN_LATENCY markers
  CONFERENCE_RUNTIME_PROJECTION (phase, connected, awaiting)
  SRD_WATCHDOG_GAP / WATCHDOG
  lease / mutex facts (PNSRD_INST instrumentation)
```

**Operator prohibitions:**

```text
Do NOT repeat-create meetings because UI shows CONNECTING
Do NOT early-hangup because UI has not shown LIVE
Do NOT treat UI LIVE/CONNECTING as R3 PASS/FAIL
```

For 3p MESH bootstrap: **CONNECTING UI is contract-consistent**; `runtime ACTIVE` / `ICE CONNECTED` may still indicate a functioning substrate while waiting for SRD#2 hang.

### Pre-flight (before ACCEPT)

1. Confirm host APK embeds **`build=pnsrd-inst-1`** (logcat or APK strings).
2. Start collector **once**:

```powershell
cd talkback
.\scripts\pnsrd-hang-capture-start-run.ps1
```

3. **After collector is running:** do **not** run `logcat -c` again (manual or scripted repeat). Preflight clear via `start-run.ps1` only.

### Field operation (ONLY)

```text
M02 ACCEPT
  → SRD #1 PASS
  → wait stable (no UI thrash)

M03 ACCEPT
  → wait SRD #2 on host
```

**Hang trigger (all required):**

```text
SRD_NATIVE_CALL_ENTER
  +
~5s with no SRD_NATIVE_CALL_EXIT
  +
SRD_WATCHDOG_GAP (TIER_JNI)
```

**Immediately on trigger** (do **not** force-stop first):

```powershell
.\scripts\pnsrd-hang-capture-on-hang.ps1 `
  -LogDir "logs\pnsrd-hang-capture-YYYYMMDD-HHMMSS" `
  -FailingEdge M03
```

Then **stop touching UI** — no hangup · no retry · no PTT switch · no `am force-stop` · no `pm clear`.

After capture review: `pnsrd-hang-capture-stop-run.ps1` when stacks + `PNSRD_INST` saved.

### Expected repro pattern

```text
M02 SRD #1  → PASS + DOMAIN_IDLE_E3
M03 SRD #2  → TIER_JNI / WATCHDOG_GAP on host
```

Target closure chain (adjudicate in §4b):

```text
AudioTrack owner
    ↕
shared mutex X
    ↕
worker Lock(X)
    ↕
signaling Pushdown / SetRemoteContent
```

### Prohibited during R3 (explicit)

```text
New contract · new Java gate · new watchdog · 4PC/5PC lab
re-prime · PC reset · force-release · Factory partition
SRD retry before on-hang · hangup to "recover" before capture
pm clear · am force-stop · kill -9 · lease manipulation
Expand lab matrix · add instrumentation telemetry layers
```

Scripts: `talkback/scripts/pnsrd-hang-capture-*.ps1`

---

## 4b. R3 Adjudication (FROZEN 2026-08-26)

Write **`$CAP\ADJUDICATION.txt`** + **`$CAP\R3-VERDICT.txt`** after on-hang review.  
Use **`inst-events.log`** (`PNSRD_INST`) + **`M01-full.log`** (`Talkback` watchdog) · adjudication window = **ord-2 hang only** (timestamp ≥ ord-2 `SRD_NATIVE_CALL_ENTER`).

**Expected closure pattern** (design doc §7): same mutex ptr · `ownerTid=15408` (AudioTrack) · worker blocked · signaling in pushdown bracket without EXIT.

### Outcome 1 — S2-b1 CONFIRMED

**Condition:** mutex owner graph **closes** in `PNSRD_INST`:

```text
AudioTrack thread
  MUTEX_ACQUIRE_EXIT mutex=X ownerTid=15408

worker
  MUTEX_ACQUIRE_ENTER mutex=X
  LOCK_OWNER_SNAPSHOT ownerTid=15408

signaling
  PUSHDOWN_MEDIA_DESCRIPTION_ENTER
  SET_REMOTE_CONTENT_ENTER

AudioTrack
  MARSHAL_ENTER → no MARSHAL_RETURN
```

**Verdict:** `R3=CAPTURED` · **S2-b1 CONFIRMED** · `mutex_closure=CLOSED`

**Next (immediate):**

```text
STOP instrumentation line — no further inst captures · no new telemetry
→ Minimal Native Fix Design (separate gate; still no impl IA)
```

### Outcome 2 — mutex graph does not close

**Condition:** holder tid ≠ AudioTrack hypothesis **or** mutex ptr equality fails **or** signaling not in pushdown bracket **or** graph contradicts S2-b1.

**Verdict:** `R3=CAPTURED` · **S2-b1 REFUTED**

**Next:**

```text
Re-route S2 / S3 / S7 from new graph
No Minimal Native Fix Design until new closure
No Java SRD / lease / UI workaround
```

### Outcome 3 — instrumentation evidence insufficient

**Condition:** hang repro missing **or** `PNSRD_INST` ambiguous (filter drop · no ptr equality · no TIER_JNI window).

**Verdict:** `R3=NOT CAPTURED` (or `INCONCLUSIVE` if hang occurred but graph unusable)

**Next (only allowed branch):**

```text
Do NOT add telemetry layers
→ native debugger / unstripped build (separate authorization)
```

### R3 gate state (do not regress on PASS runs)

```text
Prior R3 PASS (both SRDs complete, no WATCHDOG_GAP)  ≠  native fault resolved
Instrumentation regression check only — mutex_closure stays OPEN until Outcome 1
```

---

## 4c. Legacy pre-stage (stack-only — superseded by §4a for R3)

**Pre-stage on capture laptop** (before conference):

```powershell
cd talkback
.\scripts\pnsrd-hang-capture-start-run.ps1
```

On **TIER_JNI hang** (do not force-stop first):

```powershell
.\scripts\pnsrd-hang-capture-on-hang.ps1 -LogDir "logs\pnsrd-hang-capture-YYYYMMDD-HHMMSS"
.\scripts\pnsrd-hang-capture-stop-run.ps1 -LogDir "logs\pnsrd-hang-capture-YYYYMMDD-HHMMSS"
```

Scripts: `talkback/scripts/pnsrd-hang-capture-*.ps1`

---

## 5. Hang tier confirmation

### 5.1 Primary signal (host log)

Search failing edge (ordinal #2 or #3 host SRD):

```text
SRD_NATIVE_CALL_ENTER
```

**TIER_JNI** (primary defect class):

```text
SRD_WATCHDOG_GAP ... lastEvent=SRD_NATIVE_CALL_ENTER missing=NATIVE_CALL_EXIT state=NATIVE_DOMAIN_EXECUTION_ACTIVE
```

Also expect **absence** of (before watchdog):

```text
SRD_NATIVE_CALL_EXIT
SRD_JNI_RETURN
SRD_CALLBACK_ENTER
```

### 5.2 Secondary: TIER_PRE_JNI

```text
SRD_WATCHDOG_GAP ... missing=NATIVE_CALL_ENTER state=PRE_NATIVE_CALL
  OR missing=JNI_RETURN with lastEvent in {SRD_MUTEX_WAIT_BEGIN, SRD_MUTEX_ACQUIRED}
```

Record tier in artifact; **stack capture protocol is the same**, but failing thread may not yet be inside `setRemoteDescription`.

### 5.3 Identify failing edge

From `SRD_NATIVE_CALL_ENTER` or `SRD_WATCHDOG_GAP`:

```text
edgeKey=<sessionId>|<remoteModuleId>
thread=tb-edge-<sessionId>|<remoteModuleId>
holderEdge=...
```

Note **host SRD ordinal** (1st / 2nd / 3rd accept in session) from preceding `APPLY_REMOTE_ANSWER` + `DOMAIN_IDLE_E3` sequence.

---

## 6. Capture sequence (execute in order, do not skip)

Hang detected → **capture first, debate later.** Target **< 60s** from last `SRD_NATIVE_CALL_ENTER` to SIGQUIT.

```text
① Confirm tier + failing edgeKey + sessionId (log grep)
② Record watchdog / lease snapshot (log grep, no app action)
③ SIGQUIT stack dump on HOST device (M01 runs Talkback + WebRTC)
④ Thread name list (/proc) — map Java + native threads
⑤ Timeline slice ±2s around SRD_NATIVE_CALL_ENTER
⑥ Optional: failing peer device log if USB present (M02/M03)
⑦ Write ADJUDICATION.txt — do NOT force-stop app until stacks saved
```

### 6.1 Watchdog + lease snapshot (read-only grep)

From `$CAP\M01-full.log` (or live logcat):

```powershell
Select-String -Path "$CAP\M01-full.log" -Pattern `
  "SRD_NATIVE_CALL_ENTER|SRD_NATIVE_CALL_EXIT|SRD_JNI_RETURN|SRD_CALLBACK_ENTER|SRD_WATCHDOG_GAP|NATIVE_DOMAIN_EXECUTION_|NATIVE_DOMAIN_LEASE_|NATIVE_DOMAIN_HOLDER_|DOMAIN_IDLE_E3|DOMAIN_BLOCKED|WEBRTC ICE|SRD_MUTEX" `
  | Select-Object -Last 80
```

Save as `$CAP\watchdog-lease-snapshot.txt`.

### 6.2 Process PID (host)

```powershell
adb -s HTUBB21B09220661 shell pidof com.talkback.appprod
# → $PID
```

Record in `$CAP\PID.txt`.

### 6.3 Native + Java stacks — SIGQUIT (authorized)

**Non-terminating.** Process continues after dump (may append to logcat).

```powershell
adb -s HTUBB21B09220661 shell kill -3 $PID
Start-Sleep -Seconds 3
adb -s HTUBB21B09220661 logcat -d -v threadtime > "$CAP\M01-post-sigquit.log"
```

If SIGQUIT trace insufficient (some OEMs):

```powershell
adb -s HTUBB21B09220661 shell debuggerd -b $PID 2>&1 | Out-File "$CAP\debuggerd.txt"
```

**Prohibited:** `kill -9`, `am force-stop`, `pm clear`.

### 6.4 Thread inventory (must include)

Extract from `$CAP\M01-post-sigquit.log` or:

```powershell
adb -s HTUBB21B09220661 shell "ls /proc/$PID/task | wc -l"
adb -s HTUBB21B09220661 shell "for t in /proc/$PID/task/*; do cat `$t/comm; done" `
  | Sort-Object -Unique | Out-File "$CAP\thread-comm.txt"
```

**Mandatory threads to locate in stack dump:**

| Thread | Role |
|--------|------|
| `talkback-coordinator` | Control plane (must not be blocked in JNI for TIER_JNI) |
| `tb-edge-<session>\|<module>` | **Failing edge** — expected blocked in JNI |
| `signaling_thread` | Factory shared — S1/S2 primary |
| `worker_thread` | Media engine — S2/S3 |
| `network_thread` | ICE/transport — S2 |
| `AudioRecordThread` / `AudioTrackThread` / `WebRtcAudio*` | ADM — S3/S7 |

### 6.5 Timeline slice (±2s)

Anchor on failing edge’s `SRD_NATIVE_CALL_ENTER` timestamp from log.

Export window (adjust `T0` / `T1`):

```powershell
# Manual: note ENTER line timestamp T_enter from log
# Export lines between T_enter-2s and T_enter+2s to:
#   $CAP\timeline-window.txt
```

**Required events in window (if present):**

```text
APPLY_REMOTE_ANSWER / CONFERENCE_MEDIA_EDGE_SRD_APPLYING
NATIVE_DOMAIN_LEASE_REQUEST / GRANTED
NATIVE_DOMAIN_EXECUTION_ENTER
SRD_MUTEX_WAIT_BEGIN / SRD_MUTEX_ACQUIRED
SRD_NATIVE_CALL_ENTER
WEBRTC ICE -> / WEBRTC_NEGOTIATION
NATIVE_DOMAIN_HOLDER_SNAPSHOT
DOMAIN_IDLE_E3 (prior ordinal only)
SRD_WATCHDOG_GAP (if already fired)
```

Log tag filter for live tail:

```text
adb logcat -v threadtime -s Talkback:I
```

Production SRD boundaries log on **`Talkback`** tag (`TalkbackLog`).

---

## 7. Mechanism adjudication (from stacks)

Fill in `$CAP\ADJUDICATION.txt` after review.

### S1 — Edge → signaling `Marshal` wait

**Stack pattern:**

```text
tb-edge-* blocked in:
  PeerConnection / JNI setRemoteDescription
  rtc::Event::Wait / ConditionVariable / pthread_cond_wait
signaling_thread:
  runnable queued OR slow OR blocked elsewhere
```

**Routing:** native/thread fix review (not Java re-prime).

### S2 — Signaling ↔ worker `Invoke` cycle

**Stack pattern:**

```text
signaling_thread:
  rtc::Thread::Invoke / Send / BlockingCall
  SdpOfferAnswerHandler / ApplyRemoteDescription
worker_thread:
  blocked in Invoke back to signaling OR voice engine lock
tb-edge-*:
  waiting on signaling (same as S1 surface)
```

**Routing:** native/thread fix review — name lock/order in ADJUDICATION.

### S3 / S7 — ADM / audio native lock

**Stack pattern:**

```text
worker_thread OR signaling_thread:
  WebRtcVoiceEngine / AudioDeviceModule / JavaAudioDeviceModule JNI
AudioRecordThread / AudioTrackThread:
  holding monitor / nativeLock / audio I/O
tb-edge-*:
  waiting above
```

**Routing:** runtime capability / architecture containment review — **stop Java SRD patches**.

### Inconclusive stack

```text
Stacks present but no clear waiter / waitee
  → note INCONCLUSIVE
  → next: WebRTC internal trace / debugger (separate authorization)
  → do NOT expand lab matrix
```

---

## 8. Artifact layout

```text
talkback/logs/pnsrd-hang-capture-YYYYMMDD-HHMMSS/
  CAPTURE_README.txt
  PID.txt
  M01-full.log
  M01-post-sigquit.log
  debuggerd.txt              (optional)
  thread-comm.txt
  watchdog-lease-snapshot.txt
  timeline-window.txt
  M0x-peer.log               (optional)
  ADJUDICATION.txt
```

---

## 9. ADJUDICATION.txt template

```text
capture_id: pnsrd-hang-capture-YYYYMMDD-HHMMSS
verdict: PASS | INCONCLUSIVE | FAIL_CAPTURE
tier: TIER_JNI | TIER_PRE_JNI
session_id:
failing_edge:
host_srd_ordinal: 1 | 2 | 3
device_host: M01 HTUBB21B09220661
device_failing_peer: M02 | M03 | ...
package: com.talkback.appprod
webrtc: stream-webrtc-android:1.3.10

watchdog:
  lastEvent=
  missing=
  state=
  holderEdge=

lease_at_hang:
  holderEdgeKey=
  DOMAIN_IDLE_E3_before_this_srd: yes | no

timeline_anchor: SRD_NATIVE_CALL_ENTER @ HH:MM:SS.mmm

stacks:
  failing_edge_thread:
    name=
    blocked_in=   # JNI / wait / lock
  signaling_thread:
    state=        # running / blocked / idle
    top_frames=
  worker_thread:
    state=
    top_frames=
  network_thread:
    state=
    top_frames=
  audio_threads:
    names=
    top_frames=

mechanism_class: S1 | S2 | S3 | S7 | S1+S2 | INCONCLUSIVE
mechanism_statement:  # one sentence: edge waits on X because Y

next_route:
  native_fix_review | architecture_capability_review | lower_trace_authorization

prohibited_actions_confirmed: no_force_stop no_retry no_lease_release yes

notes:
```

---

## 10. Runbook PASS criteria

| Outcome | Runbook result |
|---------|----------------|
| `mechanism_class` ∈ {S1, S2, S3, S7} with **named wait site** | **PASS** |
| Stacks captured but wait site ambiguous | **INCONCLUSIVE** — do not patch Java; authorize lower trace only |
| Hang confirmed but no stacks / SIGQUIT failed | **FAIL_CAPTURE** — retry capture on **next** hang only; still no product change |

---

## 11. Post-capture routing (frozen)

```text
S1 or S2 narrowed     → native/thread minimal fix **design review** (not IA yet)
S3 or S7 narrowed     → architecture / runtime capability review — no Java SRD gate
INCONCLUSIVE          → WebRTC trace / debugger — separate gate
2PC/3PC lab           → remain STOP
Java re-prime         → remain NOT SELECTED
Field contrast matrix → remain STOP
```

---

## 12. Quick reference — log grep (host)

```powershell
rg "SRD_NATIVE_CALL_ENTER|SRD_WATCHDOG_GAP|DOMAIN_IDLE_E3|NATIVE_DOMAIN_HOLDER" $CAP\M01-full.log
rg "SRD_NATIVE_CALL_ENTER" $CAP\M01-full.log  # find failing edge + time
rg "signaling_thread|worker_thread|network_thread" $CAP\M01-post-sigquit.log
rg "tb-edge-" $CAP\M01-post-sigquit.log
```

---

## 13. Related artifacts

| Doc | Role |
|-----|------|
| [primary-native-srd-fault-minimal-contrast-run-card-20260825.md](./primary-native-srd-fault-minimal-contrast-run-card-20260825.md) | Field markers · topology |
| [primary-native-srd-fault-native-brief-9b-20260825.md](./primary-native-srd-fault-native-brief-9b-20260825.md) | S1–S7 hypothesis classes |
| [primary-native-srd-fault-native-phase2-source-trace-20260825.md](./primary-native-srd-fault-native-phase2-source-trace-20260825.md) | Source file map |
| [primary-native-srd-fault-pinned-instrumentation-design-20260825.md](./primary-native-srd-fault-pinned-instrumentation-design-20260825.md) | Inst event catalog · §7 closure pattern · design §8 outcomes |
| [conference-mesh-live-presentation-contract-review-20260826.md](./conference-mesh-live-presentation-contract-review-20260826.md) | Track B CLOSED · 3p MESH UI non-authoritative |

---

## 14. Status

```text
Production hang stack capture runbook    AUTHORIZED
R3 Run Contract (§4a)                    FROZEN 2026-08-26
R3 Adjudication (§4b)                    FROZEN 2026-08-26
Track B MESH LIVE                        CLOSED — UI CONNECTING non-authoritative
Product code changes                     NOT AUTHORIZED
Next field action                          Execute §4a → §4b Outcome 1/2/3
Lab (2PC / 3PC / 4PC+)                   STOP — do not expand
Instrumentation telemetry                STOP after Outcome 1 (enter fix design)
R3                                       NOT CAPTURED (mutex_closure OPEN)
Minimal native fix design                BLOCKED (Outcome 1 only)
```

---

## 15. Investigation chain (FROZEN)

### Mainline status (2026-08-26)

```text
Track B — MESH LIVE                      CLOSED (Q1=NO · no amendment)
Track A — Primary Native SRD             ONLY remaining line
GCT / B1 / lease                         FROZEN
Primary SRD RCA                          PASS (ordinal #2 TIER_JNI)
R2-B production inst hooks               PASS
2PC / 3PC lab                            STOP (all PASS — no repro)
Instrumentation (pnsrd-inst-1)           runtime verified · ONE R3 capture pending
Mutex object + owner graph               OPEN → §4a ONE SHOT closes it
R3                                       NOT CAPTURED
Minimal native fix design                BLOCKED (Outcome 1 only)
```

**NOT IN SCOPE:** Java Coordinator · Barrier · Lease · PENDING · `sharedFactorySrdReprime()` · ordinal gate · 4PC+ lab · field contrast matrix.

**E3 fact (frozen):** `E3 = Java lease idle` · **`E3 ≠ native negotiation context idle`**.

### Stack → RCA order (do not skip)

```text
1. Production native stack (not log inference)
2. M125 pinned source对照 (b831eb81… / branch-heads/6422)
3. Name concrete wait site
4. Attribute:
     upstream M125 behavior
   + Stream m125.4 patch delta (PROVISIONAL — stamp may hide patches)
   + Talkback usage (1 Factory · 1 ADM · SharedLocalAudio · real ICE)
```

Do **not** label “upstream WebRTC bug” until step 4 completes.

### Candidate → stack discriminant

```text
S1  signaling backlog          → edge @ Marshal; signaling stuck / queued behind PC-A
S2  signaling↔worker deadlock  → Invoke chain signaling ↔ worker
S3  VoiceEngine / ADM          → worker in channel / ADM init
S4  SharedLocalAudio           → voice setup with shared track id
S7  ADM nativeLock             → JavaAudioDeviceModule / record thread
```

```text
Production Native SRD Fault
        ↓
2PC / 3PC lab — no repro
        ↓
Lab STOP (no 4PC+ · no production replay matrix)
        ↓
Wait for real production TIER_JNI
        ↓
Runbook §6 — native stack capture (read-only)
        ↓
Name actual wait site (thread / lock / Invoke / frame)
        ↓
Route:
  S1/S2 → native thread / cross-thread sync fix review
  S3/S7 → ADM / shared media-runtime capability review
```

**Already proven (do not re-prove on next hang):** ordinal #2 host SRD can TIER_JNI.

**Still required from next hang:** *where* the failing `tb-edge-*` thread blocks in native.

### Frozen — not selected / deferred

```text
force-release           DEFERRED
PC / native reset       DEFERRED
Factory partition       DEFERRED
B2                      HOLD
Java re-prime           NOT SELECTED
ordinal gate            NOT SELECTED
Implementation IA       BLOCKED
Field contrast matrix   STOP
9a Java telemetry       DEFERRED (unless stack names a Java-visible target)
```

### Investigation exit principle (ONE SHOT)

> **Next** production hang: runbook stack capture is the **last** open-ended observation round. If stacks **still cannot name the wait site**, do **not** expand field/lab or Java telemetry. **Authorize pinned-source instrumentation** or pinned WebRTC debug build **directly**.

```text
stack inconclusive after runbook PASS attempt
    ↓
NOT: expand Java SRD_BOUNDARY / snapshot tiers · NOT: 4PC+ lab · NOT: field matrix
NOT: sharedFactorySrdReprime() or any Java “万能 reset”
YES: authorize lower native trace OR pinned WebRTC commit debug build (@ b831eb81…)
```

This closes the observation arc unless native evidence names a concrete intervention.
