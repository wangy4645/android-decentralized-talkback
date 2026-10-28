# Media Engine Ownership — Hangup Ordering & Reuse Gate

**ID:** media-engine-ownership-hangup-gate  
**Date:** 2026-08-24  
**Version:** v0.1.1  
**Type:** Architecture Contract · Minimal Fix Design  
**Status:** **FROZEN — IMPLEMENTATION AUTHORIZED** (§5.1–5.3 only)

**Field evidence:** `talkback/logs/meeting-end-liveness-v3-20260824-110815`  
**Prerequisite:** [Coordinator ↔ Media Execution Boundary](./coordinator-media-execution-boundary.md) v0.1.1 (A0.1–A0.3)

**Upstream (orthogonal — MUST NOT amend):**

| Contract | Role |
|----------|------|
| [ADR-0056](../adr/0056-conference-topology-first.md) | Topology · membership |
| [Failure Domain Contract](../analysis/conference-failure-domain-contract.md) | L1–L4 fault taxonomy |
| Decision 1 / CFC-1 | Containment projection — not this chain |

---

## 1. Problem statement (v3 RCA)

Hangup exhibited **Media Lifecycle death while Coordinator remained live**:

```text
MEDIA_RELEASE_SCHEDULED (mediaSessionReuse)
    ↓
EDGE_TASK_START (grp:CH-01|Mxx)
    ✗ EDGE_TASK_END
    ✗ MEDIA_RELEASED
    ✗ MEDIA_PROVISION_REQUESTED
```

Concurrently, conference teardown on **different edge keys** for the **same `moduleId`**:

| Module | Conference lane (`302fb48d\|Mx`) | Reuse lane (`grp:CH-01\|Mx`) |
|--------|----------------------------------|------------------------------|
| M02 | PLAYBACK `setRemotePlaybackEnabled` START, no END | `factory.release()` START, no END |
| M03 | SRD native ENTER since ~38s earlier, no EXIT; PLAYBACK queued | `factory.release()` START, no END |
| M04 | `stopCapture` START, no END | `factory.release()` START, no END |

**Root cause class:** **ownership violation**, not missing log points.

```text
edgeKey executor isolation
    ≠
underlying MediaEngine ownership isolation

ModuleMediaEngineFactory
    = one WebRtcAudioEngine per moduleId (process-wide for mesh)
```

Two executor lanes invoked **concurrent native lifecycle** on the same underlying engine → release transaction never completes → A0.1 handoff blocked.

---

## 2. Frozen invariant — single lifecycle owner

> **For each `moduleId`, at most one lifecycle owner MAY hold the underlying `MediaEngine` at any time.**

This invariant is **not** bound to Conference vs Group product concepts. It is bound to **runtime identity**:

```text
moduleId
    ↓
ModuleMediaEngineFactory entry
    ↓
RealWebRtcAudioEngine (+ shared PeerConnectionFactory domain)
```

**Forbidden implicit assumption:**

```text
edge executor thread  =  isolated engine instance   ✗
```

---

## 3. EngineOwnership model (normative for minimal fix)

Per-`moduleId` ownership record (conceptual; implementation MAY be a small gate class):

```text
EngineOwnership[moduleId]

owner:
  CONFERENCE_EDGE   — conference session edge teardown / negotiation on that engine
  GROUP_REUSE       — scope transition reuse (CONFERENCE → GROUP) release/provision
  PROVISION         — cold bootstrap provision (future waves)
  NONE              — no active owner; engine absent or fully released

state:
  IDLE
  RELEASE_REQUESTED
  RELEASING
  RELEASED          — ownership handoff barrier (see boundary doc §4.1)
  FAILED            — release terminal; ownership **NOT** released (see §3.1)
```

### 3.1 Gate protects acquire **and** release (normative)

The gate MUST constrain **all lifecycle mutations** on the underlying engine for a `moduleId`, not only provision/acquire:

```text
moduleId lifecycle mutations gated:
  acquire      (factory.getOrCreate)
  release      (factory.release)
  reuse        (mediaSessionReuse → release then provision)
  teardown     (close / edge dispatch release)
```

**Forbidden partial gate:**

```text
CONFERENCE owner → block GROUP acquire only   ✗
```

**Required:**

```text
WHILE owner=CONFERENCE_EDGE AND state ∉ { RELEASED }:
  GROUP_REUSE MUST NOT factory.release(moduleId)
  GROUP_REUSE MUST NOT factory.getOrCreate(moduleId)
  GROUP_REUSE MUST NOT enter mediaSessionReuse release transaction
```

v3 RCA conflict was **both lanes mutating the same engine concurrently** (conference teardown **and** GROUP `factory.release()`). Blocking acquire alone is insufficient.

### 3.2 `FAILED` ≠ ownership released (normative)

```text
CONFERENCE release success (MEDIA_RELEASED):
  owner → NONE
  state → RELEASED
  GROUP may acquire (still subject to A0.1 next-turn provision)

CONFERENCE release failure (MEDIA_RELEASE_FAILED):
  owner → RETAINED (CONFERENCE_EDGE / UNKNOWN)
  state → FAILED
  GROUP MUST NOT acquire
  MUST NOT treat FAILED as ownership handoff barrier
```

> **`MEDIA_RELEASE_FAILED` is a control-plane terminal fact, not proof that runtime ownership was released.**

Minimal implementation MAY collapse RETAINED/UNKNOWN into `owner=CONFERENCE_EDGE, state=FAILED` without extra enum values — but MUST NOT map `FAILED → NONE` or allow happy-path GROUP bootstrap.

State machine (conference owner):

```text
NONE
  ↓ acquire (conference)
CONFERENCE_OWNED (IDLE)
  ↓ release requested
RELEASING
  ├── success → RELEASED → NONE   (handoff barrier)
  └── failure → FAILED / RETAINED  (no GROUP happy path)
```

### Owner acquisition rules

| Action | Requires |
|--------|----------|
| Conference edge media op on `moduleId` | `owner == CONFERENCE_EDGE` OR engine not yet registered |
| `mediaSessionReuse` / `factory.release(moduleId)` (GROUP) | conference owner **released** (`state == RELEASED` → `owner == NONE`) |
| `factory.getOrCreate(moduleId)` (GROUP provision) | same as above + A0.1 next coordinator turn |
| `MEDIA_PROVISION_REQUESTED` | `state == RELEASED` (happy path) only — **not** after `FAILED` |

### Cross-lane rule (critical)

```text
WHILE owner=CONFERENCE_EDGE AND state in { IDLE, RELEASE_REQUESTED, RELEASING, FAILED }:
  GROUP_REUSE MUST NOT call factory.release(moduleId)
  GROUP_REUSE MUST NOT call factory.getOrCreate(moduleId)
  GROUP_REUSE MUST NOT enter mediaSessionReuse
```

Violation = **A0 FAIL** (runtime overlap), regardless of coordinator turn separation.

---

## 4. Current vs target hangup sequence

### Current (violating)

```text
hangupInternal()
    releaseConferenceChannelForGroupPtt()     ← MEETING_END transition; can kick GROUP bootstrap
    releaseSessionMedia()                     ← conference edge teardown (async)
         +
    (same coordinator era) meshCallInternal / requestGroupEngine
         → mediaSessionReuse → factory.release() on grp:CH-01 lane
```

Conference teardown and GROUP reuse release **overlap on the same engine**.

### Target (minimal fix)

```text
MEETING_END
    ↓
(1) Conference teardown transaction — per affected moduleId
    MEDIA_RELEASE_REQUESTED  owner=CONFERENCE_EDGE
    releaseSessionMedia + conference edge release completion
    ↓
    MEDIA_RELEASED | MEDIA_RELEASE_FAILED
    owner → NONE (RELEASED) or FAILED terminal
    ↓
(2) Channel / GROUP transition (governance only until step 1 complete)
    releaseConferenceChannelForGroupPtt effects that imply reuse
    MUST NOT dispatch factory.release / requestGroupEngine until (1) barrier
    ↓
(3) GROUP reuse / provision transaction — per moduleId
    owner=GROUP_REUSE
    MEDIA_PROVISION_REQUESTED (after RELEASED, separate coordinator turn per A0.1)
    ↓
    MEDIA_PROVISIONED | MEDIA_PROVISION_FAILED
```

**Ordering principle:** **Conference release transaction completes (fact) before GROUP reuse may acquire ownership.**

A0.1 remains in force:

```text
RELEASED → (next coordinator turn) → PROVISION_REQUESTED
```

The ownership gate adds:

```text
Conference owner released → (fact) → GROUP owner may acquire
```

Without both, next-turn provision still risks runtime overlap.

---

## 5. Three authorized minimal changes

Implementation MUST be limited to these three; no scope expansion without new ADR.

### 5.1 Hangup ordering

**Reorder and gate `hangupInternal` (host/local conference hangup path):**

1. Begin conference **media release transaction** for all mesh module IDs on the session (`releaseSessionMedia` dispatch — already async on conference edge keys).
2. **Do not** call `releaseConferenceChannelForGroupPtt` side effects that trigger `requestGroupEngine` / `mediaSessionReuse` until step 1 emits `MEDIA_RELEASED` or `MEDIA_RELEASE_FAILED` for **all** modules in the hangup barrier set (or explicit partial-failure policy — see §6.2).
3. After barrier, run channel release / GROUP transition / `meshCallInternal` bootstrap.

**Touch points (reference only):**

- `TalkbackCoordinator.hangupInternal` — swap ordering relative to `releaseConferenceChannelForGroupPtt`
- `releaseConferenceChannelForGroupPtt` — split **governance transition** from **GROUP media bootstrap**
- `onGovernanceTransitionTerminal` / `reconcileGroupMeshInternal` — MUST NOT invoke `requestGroupEngine` while conference release barrier open

### 5.2 Engine ownership gate

Introduce a **thin gate** consulted by:

- `MediaSessionManager.requestEngine` / reuse path (`mediaSessionReuse`)
- `MediaSessionManager.beginAsyncFactoryRelease`
- Conference edge release entry (`releaseSessionMedia` → `releaseGroup` dispatch)

Gate responsibilities:

```text
acquire(owner)           — fail or defer if conflicting owner/state
markReleasing()          — RELEASE_REQUESTED → RELEASING
markReleased()           — → RELEASED, owner NONE
markFailed(cause)        — → FAILED; block happy-path provision
mayProvision()           — true only on RELEASED + separate turn (A0.1)
```

**Defer, do not block coordinator:** pending GROUP reuse requests queue on the gate until `MEDIA_RELEASED`; emit existing `MEDIA_RELEASE_DEFERRED_CREATE` (or successor fact) with **`cause=ownership_barrier`**.

### 5.3 Explicit handoff facts

Reuse existing A0 vocabulary; add **ownership attribution** on defer/barrier logs:

| Fact | Meaning |
|------|---------|
| `MEDIA_RELEASE_REQUESTED` | Release submitted; `owner` recorded |
| `MEDIA_RELEASED` | **Ownership released** — GROUP reuse MAY acquire (still not same-tick as provision) |
| `MEDIA_RELEASE_FAILED` | Terminal — **no** happy-path `requestGroupEngine` |
| `MEDIA_PROVISION_REQUESTED` | Only after `MEDIA_RELEASED` + A0.1 coordinator turn |
| `ENGINE_OWNERSHIP_DEFERRED` | Reuse/provision blocked by active owner (optional alias for deferred create) |

`MEDIA_RELEASE_FAILED` path unchanged from boundary doc §4.2: explicit recovery only.

---

## 6. Edge cases (minimal policy)

### 6.1 Partial module barrier

Hangup barrier set = `meshMediaModuleIds(session)` (+ initiator if applicable).

```text
All modules RELEASED     → open GROUP reuse barrier
Any module RELEASE_FAILED  → do NOT happy-path bootstrap; surface explicit failed/reconcile state
```

No silent provision on failed subset.

### 6.2 MEETING_END transition vs media barrier

`channelGovernance.beginTransition(MEETING_END)` MAY start for UX/readiness **before** media completes.

**Forbidden:** any code path from that transition that calls `meshCallInternal` → `requestGroupEngine` until media ownership barrier clears.

Readiness snapshots MAY continue; **media reuse MAY NOT**.

### 6.3 Remote hangup / participant leave

Participant `leaveConferenceInternal` calls `releaseSessionMedia` without full channel GROUP bootstrap — lower risk but gate still applies if reuse follows.

Host `hangupInternal` is the **primary** fix target (v3 repro).

### 6.4 M03-style stuck native (SRD ENTER, no EXIT)

Ownership gate **does not** fix native hang inside conference lane.

It **prevents** GROUP reuse from compounding overlap by calling `factory.release()` while conference lane still holds owner.

Stuck native → conference release MUST eventually emit `MEDIA_RELEASE_FAILED` via [A0.7 bounded terminality](./a0.7-bounded-release-terminality-amendment.md) watchdog (coordinator turn; **not** native kill). Ownership gate still prevents GROUP reuse overlap during RUNNING.

---

## 7. Explicit non-goals (this wave)

```text
WebRtcSharedFactory / ADM redesign
D-β · D-γ · native partition
Factory per-edge split
Negotiation Owner absorption
Release timeout policy (implementation)
CFC-1 / ADR-0056 semantic changes
Moving all JNI off coordinator (A0.2/A0.3 — subsequent wave)
```

---

## 8. Verification (field + unit)

### Must PASS

```text
Hangup host path:
  conference MEDIA_RELEASED (or FAILED) logged per module
  before first MEDIA_PROVISION_REQUESTED / mediaSessionReuse release START

Release stage chain (per module, correlate with origin via MEDIA_RELEASE_SCHEDULED):
  RELEASE_ENTER → PC_CLOSE_ENTER → PC_CLOSE_EXIT →
  SHARED_FACTORY_RELEASE_ENTER → SHARED_FACTORY_RELEASE_EXIT → MEDIA_RELEASED

No overlap window:
  grp:CH-01|Mxx mediaSessionReuse START
  while 302fb48d|Mxx conference teardown task still active on same moduleId

ENGINE_OWNERSHIP_DEFERRED present when GROUP blocked; absent after RELEASED handoff

A0.1:
  MEDIA_RELEASED and MEDIA_PROVISION_REQUESTED on distinct coordinator turns

Coordinator:
  continues logging after hangup (liveness regression)
```

### v4 adjudication (ownership gate — not Meeting success)

| Observation | Verdict |
|-------------|---------|
| Release completes; GROUP provision/invite follows | **Ownership/hangup gate PASS** |
| Release stuck; GROUP never acquire/release on same engine | **RCA → single-owner release bounded-liveness** |
| GROUP acquire/release before conference RELEASED | **Gate FAIL / bypass** |
| Release success; bootstrap still fails | Inspect A0.1/A0.2; do not roll back ownership gate |
| M03 SRD native hang alone | Independent P0.1d/CFC track |

### Must NOT claim

```text
A0 full PASS
Meeting success = ownership fixed globally
Native SRD hang eliminated
P0.1g / CFC-1 closure
```

### Unit tests (minimum)

```text
Gate: GROUP_REUSE acquire rejected while CONFERENCE_EDGE RELEASING
Gate: provision deferred until MEDIA_RELEASED fact
Hangup harness: releaseConferenceChannelForGroupPtt does not call requestGroupEngine before barrier callback
```

---

## 9. Implementation sequence (after this doc frozen)

```text
1. Freeze this contract (v0.1.1)
2. Implement §5.1–5.3 (ordering + gate + facts) — DONE
3. Release stage telemetry (RELEASE_ENTER / PC_CLOSE / SHARED_FACTORY_RELEASE) — DONE
4. Field rerun: meeting-end-liveness v4 / v5 / v5.1
5. A0.7 amendment frozen — see a0.7-bounded-release-terminality-amendment.md
6. A0.7a watchdog implementation (PR-3)
7. Only then: A0.2 / A0.3 dispatch-first waves (orthogonal)
```

Telemetry is **post-design guardrail**, not RCA tooling.

---

## 10. Relationship to Coordinator ↔ Media Execution Boundary

| Layer | Rule |
|-------|------|
| A0.1 | `RELEASED` ≠ `PROVISION` — coordinator turn separation |
| **Ownership gate (this doc)** | `CONFERENCE_EDGE` released before `GROUP_REUSE` may touch engine |
| A0.4–A0.6 · A0.5b | Release admission · bootstrap gate · runtime ingress (this doc §5) |
| **A0.7** | [Bounded release terminality](./a0.7-bounded-release-terminality-amendment.md) — RUNNING must terminalize; FAILED ≠ NONE |
| A0.2 | Coordinator must not sync-invoke media runtime |
| A0.3 | AudioBus / setInboundPcmSink inventory |

This doc **extends** A0.1; it does not replace it. Both are required to prevent cross-tick runtime overlap.

---

## Document map

```text
coordinator-media-execution-boundary.md
    A0 liveness · transaction vocabulary · handoff barrier semantics

media-engine-ownership-hangup-gate.md (this)
    moduleId engine single-owner · hangup ordering · reuse gate

Future (not authorized here)
    A0.7b actual runtime cleanup — see a0.7-bounded-release-terminality-amendment.md §7
    release stage telemetry spec (partially done)
```
