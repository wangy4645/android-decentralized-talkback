# ADR-0056 IG-2 — Anchor Audio Path Gate (Run Card v2)

## Status

**PASS (CONDITIONAL)** — field executed 2026-08-14

Evidence: `talkback/logs/ig2-anchor-audio-v2-20260814-201528/` · `ADJUDICATION.txt`

```text
Audio correctness:     PASS (4p ANCHOR Bus A–D field)
Evidence completeness: INCOMPLETE — OBS-056-01 open
CONDITIONAL exit:      CONFERENCE_AUDIO_PATH on M01 in one 4p re-smoke
```

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) · [Phase 1a MCU-lite spec](./0056-phase-1a-conference-audio-mcu-lite-spec.md)

**Prerequisite gates (CLOSED):**

```text
Phase 1a CLOSED (1a-1 … 1a-5 PASS)
IG-1 PASS (Bus module contract)
IG-2-precheck PASS (3p Conference MESH smoke — see logs/ig2-precheck-3p-mesh-smoke-20260814-194620)
```

**Deferred predecessor:** IG-2 v1 attempted on 3 participants — correctly adjudicated as **DEFERRED** (admission N&lt;4; `topology=MESH`; MCU-lite path not exercised). Do not upgrade that run to IG-2 PASS.

---

## Purpose

Validate **production** Anchor `LOCAL_AND_REMOTE` audio path on real devices:

```text
Anchor local mic
    → LocalMicFrameSource
    → ConferenceAudioBus.pushLocalMicrophoneFrame()
    → AudioMixer
    → PcmInjectionPort
    → WebRTC program track
```

**IG-2 is an audio-path gate.** It is **not** a topology migration, failover, recovery, or capacity gate.

---

## Scope boundary (frozen)

### IG-2 validates

```text
Audio path activation at N >= 4 with topology=ANCHOR
LOCAL_AND_REMOTE on Anchor
Anchor outbound + remote inbound + simultaneous local+remote
Mute = stop-push without Bus rebuild (1a-5 semantics)
Audio-path facts observable in field logs
```

### IG-2 does NOT validate

```text
topology transition (3p MESH → 4p ANCHOR upgrade)
anchor failover
recovery / obligation / ICE restart semantics
meshGeneration bump
obligationGeneration
capacity scaling (6 / 8 / 10)
ConferenceHealth UI
RecoveryEdgeProvider
```

**Do not** add `meshGeneration`, `rosterEpoch`, or recovery timeline tags to IG-2 pass criteria.

---

## Admission model (frozen)

Production threshold (do not lower for this gate):

```text
SFU_LITE_CONFERENCE_THRESHOLD_MODULES = 4
```

| Participant count | Expected media path |
|-------------------|---------------------|
| &lt; 4 | Conference **MESH** (WebRTC mesh; Bus **inactive**) |
| ≥ 4 (cold start) | Conference **ANCHOR** (`ConferenceAudioBus` MCU-lite **active**) |

**IG-2 v2 tests cold-start 4p only.** Mid-session 3→4 topology upgrade is **out of scope** (Phase 1b / separate gate).

---

## Devices

| Role | Module | Serial (lab) |
|------|--------|----------------|
| Anchor | M01 | `HTUBB21B09220661` |
| Participant | M02 | `2d73067a` |
| Participant | M03 | `MDX0220416001963` |
| Participant | M04 | **required — TBD** |

**SSID:** `happy` only

### Preflight — hard gate

```text
IF participantCount < 4 OR M04 absent:
    IG-2 CANNOT EXECUTE (DEFERRED — not FAIL)
```

All four devices must have the same APK build under test. Full-duplex conference mode enabled on all units.

---

## Evidence contract (frozen fields)

Field adjudication MUST use authoritative facts below. **Audible success alone is insufficient.**

### A. Admission / topology (Anchor device — M01)

| Field | Required | Proves |
|-------|----------|--------|
| `conferenceId` | yes | Session correlation |
| `topologyMode` | yes | `ANCHOR` (not `MESH`) |
| `anchorModuleId` | yes | `M01` |
| `participantCount` | yes | `>= 4` |

**Acceptable topology evidence (at least one):**

```text
receive-path sync … topology=ANCHOR
TOPOLOGY_SNAPSHOT … topologyKind=ANCHOR
CONFERENCE_AUDIO_PATH … topologyMode=ANCHOR
```

**NOT sufficient as sole pass evidence:**

```text
ICE_CONNECTED
remote track attached
participants=4
```

(MESH conference can satisfy transport facts without activating Bus.)

### B. Audio path (Anchor device — M01)

| Field | Required | Proves |
|-------|----------|--------|
| `conferenceId` | yes | Correlation |
| `endpointId` | yes | Local endpoint |
| `participantMediaMode` | yes | `LOCAL_AND_REMOTE` on Anchor |
| `localMicActive` | yes | Mic feed seam active |
| `mixerSourceCount` | yes | `>= 1` while relay active |
| `injectionPortState` | yes | `OPEN` while relay active |

**Acceptable Bus evidence (at least one):**

```text
ConferenceAudioBus: MCU-lite relay … mode=LOCAL_AND_REMOTE
CONFERENCE_AUDIO_PATH … participantMediaMode=LOCAL_AND_REMOTE localMicActive=true …
```

### C. Failure (IG-2-B / diagnostic only — NOT a blocker)

| Field | When |
|-------|------|
| `failureReason` | If injection failure observed |

Do **not** engineer artificial injection failures to block first IG-2 execution.

---

## Structured log tag (implementation prerequisite)

Before field execution, minimal observability MUST land:

```text
CONFERENCE_AUDIO_PATH
```

Emitted when `ConferenceAudioPathObservability.publish()` runs. **Observation only** — MUST NOT:

```text
write topology / admit recovery / mutate session
feed ConferenceAuditTimeline / RecoveryTimeline / TopologyDigest authority
add Coordinator audio-authority state cache
```

Suggested single-line shape (field-grep friendly):

```text
CONFERENCE_AUDIO_PATH conferenceId=… endpointId=… topologyMode=ANCHOR participantMediaMode=LOCAL_AND_REMOTE localMicActive=true muted=false mixerSourceCount=3 injectionPortOpen=true failureReason=
```

Implementation follows this Run Card; Run Card does not follow log fields retroactively.

---

## Log collection

```powershell
cd d:\workspace\project\talkback\talkback
$ts = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = "logs\ig2-anchor-audio-v2-$ts"
New-Item -ItemType Directory -Force -Path $outDir
foreach ($d in @("HTUBB21B09220661","2d73067a","MDX0220416001963","<M04_SERIAL>")) {
  adb -s $d logcat -G 16M
  adb -s $d logcat -c
  Start-Process -WindowStyle Hidden adb -ArgumentList "-s",$d,"logcat","-v","threadtime","Talkback:I","WebRTC:I","*:S" `
    -RedirectStandardOutput "$outDir\$($d.Substring($d.Length-4))-logcat.txt"
}
```

Archive `RUN-NOTES.txt` with T0 (conference start), case timestamps, subjective audio notes.

Stop collectors: `Get-Process adb | Stop-Process -Force`

**Primary adjudication device:** M01 (Anchor). M02–M04 corroborate downlink/uplink audibility.

---

## Test procedure — cold-start 4p only

### Step 0 — Preflight

- [ ] Four devices on `happy`
- [ ] APK identical on M01–M04
- [ ] Full-duplex conference mode ON (all)
- [ ] Log collectors running
- [ ] `CONFERENCE_AUDIO_PATH` tag present in test build

### Step 1 — Cold-start conference (T0)

1. **M01** creates Conference; invite M02, M03, **M04**
2. All four Join; wait until stable (≥ 30s after last ICE connected)
3. **M01 log MUST show** `topology=ANCHOR` and Bus activation (`MCU-lite relay` and/or `CONFERENCE_AUDIO_PATH`)
4. If `topology=MESH` at 4 participants → **STOP** (admission bug; IG-2 not executable on production policy)

Record `conferenceId`, T0.

### Case A — Anchor outbound (T0 + 0–60s)

1. M01 speaks 20–30s; M02–M04 quiet
2. **Subjective:** M02, M03, M04 hear M01 clearly
3. **M01 facts:** `localMicActive=true`, `participantMediaMode=LOCAL_AND_REMOTE`

### Case B — Remote inbound (T0 + 60–120s)

1. M01 quiet; M02 and M03 speak in turn (~15s each)
2. **Subjective:** M01 hears both remotes
3. **M01 facts:** `mixerSourceCount >= 1`, receive-path / inbound activity (auxiliary)

### Case C — LOCAL_AND_REMOTE simultaneous (primary audio criterion)

1. M02 + M03 low background talk; **M01 speaks simultaneously** (~20s)
2. **Subjective:** M01 hears remotes while speaking; remotes still hear M01
3. **M01 facts:** `LOCAL_AND_REMOTE` + `localMicActive=true` throughout

### Case D — Mute / unmute (1a-5 Bus semantics)

**Do not** use `CALL_MUTE_CHANGED` alone as pass evidence.

| Phase | Required facts (M01) | Forbidden (M01) |
|-------|----------------------|-------------------|
| Before mute | `localMicActive=true` | — |
| Mute | `localMicActive=false`, `muted=true` | `ConferenceAudioBus` clear storm; repeated `MCU-lite relay` rebuild burst tied to mute |
| Unmute | `localMicActive=true`; audibility resumes | same |

**IG-2-precheck already PASS** for mesh-path `CALL_MUTE_CHANGED` + `capture OFF` at N=3. Case D here validates **Bus stop-push**, not mesh capture.

### Case E — Injection failure (OPTIONAL — IG-2-B)

- If naturally observed: `failureReason` in `CONFERENCE_AUDIO_PATH`
- If not observed: mark **E_SKIP**; does not block IG-2 PASS

---

## Pass / conditional / fail / deferred

### PASS

All required:

```text
A. Preflight N>=4, topology=ANCHOR, Bus active
B. Case A — Anchor heard by remotes
C. Case B — Anchor hears remotes
D. Case C — simultaneous LOCAL_AND_REMOTE
E. Case D — localMicActive false/true; no Bus rebuild on mute
F. Admission + audio-path frozen fields present in M01 logs
```

### CONDITIONAL PASS

Brief ICE/reachability transient with **stable audio** and explained transport fact; topology and Bus facts remain ANCHOR + LOCAL_AND_REMOTE.

### FAIL

Any of:

```text
4p cold start but topology=MESH (admission violation)
Anchor local mic not heard by remotes
Anchor cannot hear remotes during C
localMicActive semantics wrong on mute/unmute
host != anchor mic gating wrong
topology/recovery patch required to make audio work
```

### DEFERRED (not FAIL)

```text
M04 unavailable
CONFERENCE_AUDIO_PATH not in build
IG-2 executed on N<4
```

---

## Relationship to other gates

```text
IG-2-precheck (3p MESH)     ✅ PASS — regression only; does not imply IG-2
IG-2 (4p ANCHOR Bus)        PASS (CONDITIONAL) — see logs/ig2-anchor-audio-v2-20260814-201528
Phase 1b admission design   NOT AUTHORIZED (contract review only when scheduled)
Phase 1c failover           🚫 NOT AUTHORIZED
Phase 2 Provider            🚫 NOT AUTHORIZED
#196 / Q11                  🚫 unchanged
```

---

## Post-IG-2 sequence (frozen)

```text
IG-2 Run Card v2 (this document)
    ↓
CONFERENCE_AUDIO_PATH structured log (minimal; satisfies evidence contract)
    ↓
4th device + cold-start 4p field run
    ↓
IG-2 adjudication
    ↓
IF PASS → Phase 1b admission design (contract only; impl still gated)
```

---

## Revision record

| Version | Date | Notes |
|---------|------|-------|
| v2.0 | 2026-08-14 | Cold-start 4p only; frozen fact fields; DEFERRED vs FAIL; IG-2-precheck separation; Case D = Bus localMicActive |
