# ADR-0056 Phase 1a: Conference Anchor Audio MCU-lite Specification

## Status

**ACCEPTED v2** (2026-08-14) · **Implementation NOT AUTHORIZED**

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) (v2) · **Issue:** [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197) Finding A

**Prerequisite:** Phase 0.5 contract freeze checklist items ⑨⑩ complete.

**Scope:** Conference `ANCHOR` topology audio relay on the elected anchor device. GROUP `ProgramAudioBus` is **reference only**.

**Out of scope:** RecoveryEdgeProvider wiring (Phase 2), ConferenceHealth UI (Phase 3), PTT threshold restore, track-level SFU, anchor failover storm (Phase 1c).

---

## Problem statement

Current `ConferenceAudioBus` fans N−1 inbound PCM sources into **one program track per target** without mixing:

1. Multi-speaker → garbled audio (not intelligible).
2. Anchor local microphone disabled (`PROGRAM` only).
3. Per-frame reflection into libwebrtc internals; silent failure on SDK change.

Phase 1b blocked until Phase 1a exit criteria PASS.

---

## Decision — MCU-lite first

Anchor mixes active remote PCM → **one mixed stream per downstream target** (exclude-self per target).

Track-level SFU-lite deferred until selective subscription required.

---

## Normative pipeline

```text
Remote PC → inbound decode (anchor)
         → per-source tap → AudioMixer
         → mixed PCM → PcmInjectionPort → WebRTC uplink (per target)

Anchor mic → LOCAL_AND_REMOTE path → mixer → downstream
```

```text
ConferenceAudioBus → AudioMixer → PcmInjectionPort → WebRTC
```

`PcmInjectionPort` is the **formal boundary** — Mixer MUST NOT depend on libwebrtc private fields.

---

## Canonical PCM format (frozen)

```text
Sample rate:  48 kHz
Channels:     1 (mono)
Format:       PCM16
Frame:        10 ms
Samples:      480 samples / frame
```

Mixer core unit: `480 samples × N active sources → 1 mixed frame` every 10 ms.

All inbound PCM MUST align to this format before mix (resample / channel convert as needed).

---

## AudioMixer — conceptual API

```text
AudioMixer
  addSource(sourceId, MixerSourceConfig)
  removeSource(sourceId)
  push(sourceId, PcmFrame)          // non-blocking; per-source ingress
  pollMixedFrame(): PcmFrame        // driven by mixer render clock
```

**Clock ownership:** Mixer owns render clock (`10 ms` tick). Output MUST NOT depend on which participant's `push()` arrives first. Source count (1 / 4 / 8 / 10) MUST NOT change output tick period.

---

## Source lifecycle (product semantics)

```text
ADDING → ACTIVE → REMOVING → REMOVED
```

| Transition | Requirement |
|------------|-------------|
| **Join ramp** | Gain 0→1 over **20–50 ms** (no click/pop) |
| **Leave ramp** | Gain 1→0 over **20–50 ms** |
| **Join/leave** | MUST NOT reset mixer render clock |

Source state MUST NOT flip on single missed frame.

---

## Per-source signal states

Each source MUST distinguish:

| State | Behavior |
|-------|----------|
| **audio** | Normal mix contribution |
| **silence** | VAD / energy below threshold — skip or near-zero gain |
| **missing** | Expected frame absent — brief silence fill |
| **late** | Arrived after tick — drop or defer with overrun counter |
| **stalled** | Missing beyond threshold — auditable `mixer_source_stalled`; topology change is **upper layer**, not immediate mixer remove |

PCM frame miss MUST NOT directly mutate mixer topology.

---

## Gain / clipping / limiter

**Forbidden:** `sum = A+B+C+D` → cast PCM16 without headroom.

**Required chain:**

```text
per-source gain → mix headroom (≥ 6 dB) → soft clip / limiter → PCM16
```

| Rule | Value |
|------|-------|
| Default per-source gain | Calibrated (not raw unity at 10 sources) |
| Hard clipping under nominal levels | **≈ 0** |
| Limiter as continuous level control | **Forbidden** — sustained limiter active → gain policy defect |

**Required telemetry:**

```text
mixer_limiter_active_ratio
mixer_peak_dbfs
mixer_clipping_samples
```

---

## ParticipantMediaMode (semantic — not PTT ProgramRelayMode long-term)

| Mode | Semantics |
|------|-----------|
| **LOCAL_ONLY** | `local mic → participant output` |
| **REMOTE_RELAY** | `remote sources → anchor mixer → participant` |
| **LOCAL_AND_REMOTE** | `local mic ┐` <br> `remote ───┴→ mixer → participant output` |

Conference anchor **MUST** use **LOCAL_AND_REMOTE** for user-facing transmit.

---

## PcmInjectionPort (product boundary)

**Frozen prohibitions:**

```text
No per-frame reflection.
No private libwebrtc field access.
No runCatching swallow-and-ignore on inject path.
```

**Conceptual interface:**

```text
PcmInjectionPort
  open(format: AudioFormat): Result
  write(frame: PcmFrame): Result
  close()
```

Injection failure → explicit auditable event; MUST NOT silent drop.

---

## Resource benchmark curve (mandatory)

CPU / memory / temperature MUST be measured at **each** step — not first at 10 participants:

```text
1 → 4 → 6 → 8 → 10 sources
```

30+ min sustained at Gate 3 load (8 sources minimum) on target Android board (M01-class).

| Metric | 1a lab (≥4 sources) | Gate 2 (6p) | Gate 3 (8p) |
|--------|---------------------|-------------|-------------|
| E2E mouth-to-ear (anchor relay) p95 | ≤ 250 ms | ≤ 300 ms | ≤ 300 ms |
| Anchor CPU (audio path only) avg | ≤ 35% core | ≤ 45% core | ≤ 50% core |
| Mixer underrun rate | < 0.1% frames | < 0.05% | < 0.05% |
| Sustained thermal throttle | none (30 min) | none | none |
| Silent injection drops | 0 / 10 min | 0 / 30 min | 0 / 30 min |
| Hard clipping (nominal levels) | 0 | 0 | 0 |

Packet loss impairment test required before Gate 3 sign-off.

---

## Audio quality acceptance

### Human (authoritative)

| Scenario | Pass |
|----------|------|
| Single talker | Clear, no stutter > 200 ms |
| 2 simultaneous | Both intelligible — no alternating garble |
| 3 simultaneous | Intelligible; no robotic tearing |
| 10 simultaneous sources (lab) | Mixer stable; no sustained underrun |
| Talker join | Audible ≤ 500 ms; pop ≤ 100 ms |
| Talker leave | Updated ≤ 500 ms; ghost audio < 1 s |

### Anchor self-transmit

| ID | Criterion |
|----|-----------|
| ANCH-MIC-1 | Anchor heard by all remotes ≤ 500 ms |
| ANCH-MIC-2 | Anchor full-duplex while remotes speak |
| ANCH-MIC-3 | Failover mic path — Phase 1c (reference only in 1a) |

---

## Observability (minimum — causal chain with topology)

All events MUST include where available: `conferenceId`, `topologyGeneration`, `anchorEpoch`, `anchorId`.

```text
CONFERENCE_AUDIO_MIXER
  sourceCount, targetCount, mixTicks, underruns, overruns
  clipEvents, silentSourceSkips, limiterActiveRatio, peakDbfs

CONFERENCE_AUDIO_SOURCE
  sourceId, state=audio|silence|missing|late|stalled

CONFERENCE_AUDIO_ANCHOR_MIC
  mode=LOCAL_AND_REMOTE, captureEnabled, uplinkActive

CONFERENCE_AUDIO_INJECT
  targetModuleId, success|failure, reason
```

---

## Test matrix

| # | Setup | Action | Pass |
|---|-------|--------|------|
| T1 | 4p ANCHOR | Single remote talks | Intelligible all |
| T2 | 4p | 2 simultaneous remotes | Intelligible mix |
| T3 | 4p | Anchor talks | ANCH-MIC-1 |
| T4 | 4p | Anchor + remote duplex | No garble |
| T5 | 4p→5p | Mid-call join | Ramp, no long glitch |
| T6 | 4p | Kill one PC | Ghost < 1 s |
| T7 | Anchor | 30 min sustained | Resource gates |
| T8 | Induced inject fail | — | Explicit event, no silent drop |
| T9 | 10-source lab | 3 simultaneous | Human intelligibility |
| T10 | 1→4→6→8→10 | Benchmark sweep | Resource curve logged |

**Phase 1a PASS:** T1–T10 on M01/M02/M03 class, SSID `happy`.

---

## Phase 1a gate checklist (frozen)

| Gate item | Requirement |
|-----------|-------------|
| Mixer sources | 10 |
| PCM format | 48k / mono / PCM16 / 10 ms |
| Mixer underrun | 0 sustained |
| Hard clipping | 0 under nominal |
| Source join/leave | No audible pop |
| Anchor local mic | PASS |
| Simultaneous talkers | 10 sources tested |
| PCM injection | No per-frame reflection |
| Injection failure | Observable + terminal state |
| Mixer thread | No unbounded blocking |
| Output continuity | No scheduler-induced gaps |
| CPU / memory / temp | Curved at 1/4/6/8/10 |
| E2E latency | Measured, bounded |
| Packet loss | Tested under impairment |

---

## Phase exit

Phase 1a **COMPLETE** when:

1. MCU-lite + `PcmInjectionPort` behind Conference ANCHOR path.
2. T1–T10 PASS with observability.
3. #197 Finding A exit criteria met.
4. Resource curve 1→4→6→8→10 archived.
5. Separate authorization for Phase 1b issued.

---

## References

- [ADR-0056 v2](../adr/0056-conference-topology-first.md)
- [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197)
- Gap: `ConferenceAudioBus.kt`
- PTT reference: `ProgramAudioBus.kt` (anchor-as-floor-holder mic branch only)
