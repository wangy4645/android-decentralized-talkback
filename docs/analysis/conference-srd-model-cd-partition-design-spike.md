# Model C/D — Native Partition Design Spike + B2-2-D ADM Audit

**ID:** conference-srd-model-cd-partition-design-spike  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Design Spike · Read-Only (no implementation)  
**Status:** **COMPLETE** · **Implementation NOT AUTHORIZED** · **P0.1g HOLD**

**Prerequisite:** [B2-2-A FAIL](./conference-srd-b2-2a-native-parallel-domain-audit.md) · [B2-2 review](./conference-srd-native-resource-domain-partition-b2-architecture-review.md)  
**Code baseline:** `WebRtcSharedFactory.kt` · `SharedLocalAudio.kt` · `RealWebRtcAudioEngine.kt` · `stream-webrtc-android:1.3.10`

---

## Spike question (single)

> How to establish **at least two truly independent native SRD execution domains** without breaking Conference local audio capture and existing media contracts?

**Not:** pick “one Factory per edge” upfront · Commit 4 · P0.1g rerun · audio subsystem redesign beyond audit.

---

## Frozen upstream facts

```text
B2-2-A FAIL: one PeerConnectionFactory → shared signaling worker → no per-edge parallel native SRD
B2-1 PARTIAL: Java lease / LEASE_BUSY — safety layer only
P0.1g gate: M03 NATIVE_CALL_ENTER (no EXIT) AND M04 independent SRD_NATIVE_CALL_* → SRD_EXIT SUCCESS
```

---

## Model C — Conference-scoped native domain

### Concept

```text
Conference session
        |
        v
NativeExecutionDomain (one per conference)
        |
        v
PeerConnectionFactory (one)
        |
   +----+----+----+
   |    |    |    |
  M02  M03  M04  ...
```

### Native SRD independence

| Criterion | Assessment |
|-----------|------------|
| Separate PCs per edge | **Yes** (today) |
| Separate native signaling workers per edge | **No** — B2-2-A |
| M03 stuck → M04 native SRD proceeds | **No** — field `5ce616de` |

**Verdict:** Model C at **one Factory / multiple PCs** is **current architecture renamed**. It does **not** exceed B2-2-A. **Unacceptable for P0.1g.**

### Sub-variant C′ (multiple factories per conference, shared ADM)

```text
Conference
  ├── Factory_conf_audio?  (ADM owner)
  └── Factory per edge?      (still need ADM binding)
```

Without proved **independent signaling thread per edge**, C′ collapses to D. No separate C′ authorization.

### Model C summary

| Dimension | Rating |
|-----------|--------|
| Native SRD independence | **FAIL** |
| ADM feasibility | N/A (no P0.1g gain) |
| Resource cost | Low |
| 4–10p feasibility | Yes (already) |
| P0.1g | **Cannot PASS** |

---

## Model D — Edge-scoped native domain / Factory partition

### Concept

```text
Conference
 |
 +-- Edge M02 → NativeDomain_A → Factory_A → PC_M02
 +-- Edge M03 → NativeDomain_B → Factory_B → PC_M03
 +-- Edge M04 → NativeDomain_C → Factory_C → PC_M04
```

Each edge owns a **distinct Factory ConnectionContext** (signaling + worker + network threads per libwebrtc factory instance).

### Native SRD independence

| Criterion | Assessment |
|-----------|------------|
| M03 stuck in Factory_B signaling domain | **Theoretically isolates** M04 in Factory_C |
| Matches P0.1g shape | **Only model that can** — pending ADM/lifecycle proof |
| B2-1 compatibility | Lease `domainId` should map to **per-edge factory id** |

**Verdict:** Model D is the **only credible path** to P0.1g native independence post B2-2-A. **Not authorized for implementation** until B2-2-D closes.

### D sub-variants (for next spike — not selected here)

| ID | Pattern | ADM | Uplink audio |
|----|---------|-----|--------------|
| **D-α** | Per-edge Factory · **shared** `JavaAudioDeviceModule` | One ADM · multi-factory bind | SharedLocalAudio refactor required |
| **D-β** | Per-edge Factory · **per-edge** ADM | N× ADM | N capturers — **high Android risk** (code comment) |
| **D-γ** | Per-edge Factory · ADM only on “audio owner” factory | Hybrid | Relay / inject PCM to other factories — large contract change |

---

## B2-2-D — ADM / SharedLocalAudio audit (read-only)

### Current architecture (frozen)

```text
WebRtcSharedFactory (process singleton)
  ├── PeerConnectionFactory (one)
  ├── JavaAudioDeviceModule (one)
  │     └── SamplesReadyCallback → localOutboundSinks
  └── sdpApplyMutex

SharedLocalAudio (process singleton)
  ├── AudioSource / AudioTrack from THAT factory
  └── same track attached to every PC (notePeerAttached count)

RealWebRtcAudioEngine (per edge)
  └── acquire(factory) → create PC → SharedLocalAudio.acquireLocalTrack(factory)
```

### Audit questions → findings

#### D-1. Can one `JavaAudioDeviceModule` bind multiple `PeerConnectionFactory` instances?

| | |
|-|-|
| **Code today** | ADM created once; passed to single `PeerConnectionFactory.builder()` |
| **App evidence** | Comment: *"Multiple ADMs on Android commonly break capture/playback"* — implies **multi-ADM is risky**, not that multi-factory+one-ADM is supported |
| **libwebrtc** | Factory builder accepts external ADM; **sharing one ADM across multiple live factories is not validated in this codebase** |
| **Status** | **BLOCKED** — requires controlled lab spike (read-only harness), not production change |

#### D-2. Does `SharedLocalAudio` depend on factory-specific native objects?

| | |
|-|-|
| **Yes** | `factory.createAudioSource()` / `createAudioTrack()` — track is tied to creating factory |
| **Implication** | Per-edge Factory **cannot** attach today's single shared track to all PCs without **one track per factory** or architectural uplink split |
| **Status** | **CONFIRMED dependency** |

#### D-3. Does ADM / factory teardown on one edge affect others?

| | |
|-|-|
| **Today** | `WebRtcSharedFactory.release()` → refCount 0 → `factory.dispose()` + `audioDeviceModule.release()` + `SharedLocalAudio.release()` — **process-wide** |
| **Implication** | Per-edge factory teardown must **not** use global refCount teardown; needs **per-domain lifecycle** |
| **Status** | **CONFIRMED** — D requires new lifecycle contract |

#### D-4. Conference local capture options

| Option | Feasibility | Notes |
|--------|-------------|-------|
| Single audio source + multi-factory attach | **Unproven** | Track/factory binding blocks naive approach |
| Single ADM + multi-factory (D-α) | **Unproven** | Leading candidate if lab PASS |
| Multi ADM / multi capturer (D-β) | **High risk** | Conflicts with `WebRtcSharedFactory` comment |
| Program-relay style PCM inject (D-γ) | **Feasible but large** | Out of minimal P0.1g spike |

#### D-5. Resource cost 4–10 participants

| Resource | 1 Factory (today) | D-β (N factories + N ADM) | D-α (N factories + 1 ADM) |
|----------|-------------------|---------------------------|---------------------------|
| Signaling threads | 1 | N | N |
| Worker threads | 1 | N | N |
| Network threads | 1 | N | N |
| ADM / capturer | 1 | N (**risk**) | 1 |
| Memory | baseline | ~N × factory overhead | ~N × factory overhead |

**4–10p:** D-α is **plausibly acceptable** on paper if ADM sharing is valid; D-β is **likely unacceptable** on Android conference devices. **Field cost proof deferred** to lab spike.

### B2-2-D verdict

```text
BLOCKED for implementation authorization

Open: D-α lab proof (one ADM · multiple factories · independent SRD)
High risk: D-β (multi-ADM)
Large scope: D-γ (PCM relay)
```

---

## Evaluation matrix (spike output)

| Model | Native SRD independence | ADM / audio feasibility | Resource cost | 4–10p | P0.1g |
|-------|-------------------------|-------------------------|---------------|-------|-------|
| **C** Conference-scoped (1 Factory, N PC) | **FAIL** | OK (status quo) | Low | OK | **FAIL** |
| **C′** Multi-factory without edge isolation proof | **FAIL / unknown** | Unknown | Medium | Unknown | **FAIL** |
| **D-α** Edge Factory + shared ADM | **Likely PASS** (architecture) | **BLOCKED** — lab proof | Medium–high | Plausible | **Possible** if D-α proven |
| **D-β** Edge Factory + per-edge ADM | **Likely PASS** | **HIGH RISK** (Android) | High | Questionable | Possible but discouraged |
| **D-γ** Edge Factory + PCM relay uplink | **Likely PASS** | Feasible | High impl | OK | Possible · out of minimal scope |

---

## Architecture recommendation (spike — superseded by reassessment)

```text
REJECT Model C for P0.1g (does not beat B2-2-A FAIL).
D-α (shared ADM + per-edge Factory) REJECTED — lab FAIL
Partition track HOLD — [reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md)
Retain B2-1 as outer Java safety / lease layer (not withdrawn)
```

Evidence: [dalpha ADJUDICATION](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)

---

## P0.1g acceptance (unchanged — frozen)

```text
M03: SRD_NATIVE_CALL_ENTER (no EXIT) — allowed on M03

M04: own native domain
     → SRD_NATIVE_CALL_ENTER
     → SRD_NATIVE_CALL_EXIT
     → SRD_EXIT SUCCESS
```

P0.1g **remains HOLD** until Model D path proven and implemented under separate authorization.

---

## Proof obligations

| ID | Status | Next |
|----|--------|------|
| B2-2-A | **CLOSED FAIL** | — |
| B2-2-D | **BLOCKED** | D-α lab spike (read-only / harness) |
| B2-2-B | **REVISED** | Per-edge `NativeSrdExecutionDomain` = per-edge Factory (D-α) |
| B2-2-C | **WAIT** | `domainId` = edge factory partition key |
| B2-2-E | **HOLD** | Field P0.1g after D implementation |

---

## Prohibited

```text
Implement Model C as P0.1g fix
Implement Model D without D-α lab
Commit 4 slot reuse
P0.1g rerun
Multi-ADM D-β without D-α failure record
```

---

## Posture

```text
B2-2-A             COMPLETE — FAIL
Model B            CLOSED
B2-1               RETAIN — Commit 1–3 PASS (safety layer)
Model C            REJECTED (P0.1g)
D-α                FAIL — [adj](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)
Model C/D spike    COMPLETE (this document)
Partition track    HOLD — [reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md)
D-β / D-γ          ON TABLE / NOT AUTHORIZED
Commit 4           HOLD
IA field 1–3       ALLOWABLE (BUSY path only)
P0.1g              HOLD
Implementation     NOT AUTHORIZED
```

---

## Next

1. ~~D-α lab spike~~ — **FAIL**
2. **Architecture reassessment** — [reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md) (decision required)
3. IA field Commit 1–3 — optional; does not close P0.1g

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Model C/D spike + B2-2-D audit; C rejected; D-α leading; D-β high risk |
