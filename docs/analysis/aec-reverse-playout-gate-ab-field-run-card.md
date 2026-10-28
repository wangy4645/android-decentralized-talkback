# AEC Reverse + LocalPlayoutActivityGate A/B — Field Run Card

**Status:** AUTHORIZED (field only; no media-parameter tuning)  
**Parent evidence:** `talkback/logs/aec-reverse-field-20260927-181836` (reverse seam R1, gate ON)  
**Goal:** Under **fixed** multicast + APM reverse feed, isolate whether residual howl/feedback is explained by **acoustic loop + AEC** vs **PLC/WiFi**.  
**Not in scope:** VAD, Top-K, mixer weights, AGC, jitter, PLC policy, topology, AudioTrack ownership changes.

---

## Architecture frozen for both legs

```text
Profile 01 / ADR-0058 multicast production playout
MulticastApmReverseFeed → ProcessReverseStream (JNI)
LocalPlayoutActivityGate   ← ONLY variable between Leg A and Leg B
```

| Leg | `LocalPlayoutActivityGate.enabled` | APK label |
|-----|--------------------------------------|-----------|
| **A (control)** | `true` (default in tree) | `gate-on` |
| **B (experiment)** | `false` | `gate-off` |

**Build note:** There is no runtime/adb toggle today. Leg B requires a **single-line** build flip in `LocalPlayoutActivityGate.kt` (`enabled = false`), then `assembleDebug` + four-device install. Same WebRTC AAR / reverse JNI on both legs. Record **SHA256** of each APK in `RUN_CARD.txt`.

---

## Devices (authority: adb serial)

| Label | Serial | Connection |
|-------|--------|------------|
| M01 | `HTUBB21B09220661` | USB |
| M02 | `DSJ-2407041` | USB |
| M03 | `MDX0220416001963` | USB |
| M04 | `192.168.31.206:5555` | wireless ADB (`adb connect` before run) |

Label from log `moduleId=` / `localModuleId=` if ambiguous.

---

## Preconditions (hard — both legs)

```text
Same room layout for Leg A and Leg B (or accept INVALID_COMPARISON)
M04 on stable WiFi; avoid flap during each leg
Four devices join SAME meeting; wait until all show MULTICAST_PRODUCTION / cutover success
M03 must NOT leave early (hold until host ends leg)
No M02 speaker-volume=0 trick during A/B (that is a separate acoustic experiment)
No USER_LEAVE / hangup except scripted leg end
No membership / UI workaround / anchor fallback
```

**Leg order (recommended):** **A then B** same day, **≥10 min** cool-down between legs (new meeting each leg).

---

## Exercise (each leg ≈ 3–4 min steady multicast)

| Step | Duration | Action |
|------|----------|--------|
| 1 | 30 s | All silent after cutover (idle baseline) |
| 2 | 60 s | **M01** speaks short sentences |
| 3 | 60 s | **M03** speaks (software AEC path — watch M03) |
| 4 | 60 s | **M04** speaks (wireless TX/RX stress) |
| 5 | 30 s | All silent again |
| 6 | optional | One person talks while others play remote audio (duplex) |

Host ends meeting; **STOP** log collectors before uninstalling next leg APK.

---

## Log capture

Directory pattern:

```text
talkback/logs/aec-reverse-gate-ab-<leg>-<yyyyMMdd-HHmmss>/
  M01.log M02.log M03.log M04.log
  RUN_CARD.txt   # leg, apk sha256, operator notes
```

Per device: `logcat` with buffer ≥16M; tag filter at minimum `Talkback`, `MEETING_MEDIA_SHADOW` (full logcat OK for field).

**M04 reconnect before each leg:**

```text
adb connect 192.168.31.206:5555
```

---

## Objective gates (per leg)

| Gate | PASS criterion |
|------|----------------|
| **ENV** | Same `sessionId` on all four; `owner=MULTICAST_PRODUCTION`; no `UnsatisfiedLinkError` / native crash |
| **LIVENESS** | `A3_MIX_INPUT` with finite `mixedRmsDbfs`; `successfulPlayoutWrites` increasing; no playout starvation |
| **GATE_ARM** | Leg A: last `TX_PLAYOUT_GATE` shows **gatedFrames/(gated+pass) ≥ 25%** on ≥2 devices during duplex. Leg B: same line shows **~0% gated** (passFrames ≈ total) |
| **AUDIO#1** | Speech intelligible (subjective; note per leg) |
| **AUDIO#2** | Idle + duplex: howl/feedback severity (subjective 0–3; compare A vs B) |

If **GATE_ARM** fails (Leg B still gates), stop — build mismatch, do not adjudicate AEC.

---

## Log metrics (post-run; same session window)

Extract on each device for the multicast window only:

| Metric | Source | Use |
|--------|--------|-----|
| `mixedRmsDbfs` median / minute buckets | `A3_MIX_INPUT` + `MULTICAST_PRODUCTION` | Level stability; flag M03 late-join / early-leave |
| PLC % (per source + total) | `A3_MIX_INPUT` `realFrames` / `plcFrames` | **PLC RCA** — if Leg A vs B PLC differs >10 pts, WiFi invalidates comparison |
| `gatedFrames` / `passFrames` | `TX_PLAYOUT_GATE` | Confirm gate arm |
| `rmsDbfs` median | `G5_VA2_ADJ` | TX level; compare M02 vs listeners |
| M02→M01 active `realRmsDbfs` peak | `A3_MIX_INPUT` segments containing `M02:` | Acoustic coupling probe (expect higher on Leg B if loop dominates) |
| `funnelSelectedTopK` | shadow funnel lines | Context only — **not** a pass/fail gate |

**PLC isolation rule:** Adjudicate AEC only if per-device total PLC is within **±10 percentage points** between Leg A and Leg B for M04 (and no mid-leg WiFi flap).

---

## AEC RCA adjudication (A vs B)

| Outcome | Conditions |
|---------|------------|
| **AEC RCA CLOSED / KEEP reverse** | Leg B **AUDIO#2 worse** (more howl/feedback) than Leg A; GATE_ARM valid; PLC comparable; ENV/LIVENESS pass both legs |
| **AEC RCA OPEN** | Leg B **not worse** than Leg A while GATE_ARM valid → reverse insufficient or non-dominant; need engineering review (not automatic rollback) |
| **INVALID_COMPARISON** | Different room, M03 early teardown, M04 PLC swing >10 pts, or GATE_ARM fail |
| **PLC follow-up** | Leg A ≈ Leg B on AUDIO#2 but both noisy; high PLC on M04 → **wireless RCA**, independent track |

**Do not** conflate: gate-off may increase howl **and** increase apparent loudness (more TX energy); subjective AUDIO#2 is primary for howl; `mixedRms` is secondary.

---

## Operator checklist

```text
[ ] Leg A APK installed all four; SHA256 recorded
[ ] Log collectors running; RUN_CARD.txt started
[ ] New meeting; all four in before anyone speaks
[ ] Cutover confirmed all devices
[ ] Exercise table completed; subjective AUDIO#1/#2 noted
[ ] STOP collectors; archive logs
[ ] Cool-down
[ ] Leg B APK (gate-off only diff); repeat checklist
```

---

## References

- Reverse field R1: `talkback/logs/aec-reverse-field-20260927-181836/RUN_CARD.txt`
- Gate implementation: `LocalPlayoutActivityGate.kt` (`enabled`, `TX_PLAYOUT_GATE`, `HANGOVER_MS=300`)
- Reverse feed: `MulticastApmReverseFeed` + `AudiblePlayoutOwnershipSeam.write`
