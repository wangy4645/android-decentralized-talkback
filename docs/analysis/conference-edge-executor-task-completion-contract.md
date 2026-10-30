# Conference Edge Executor Admission and Completion Contract

**ID:** conference-edge-executor-task-completion-contract  
**Also known as:** Conference Edge Executor Availability Contract  
**Date:** 2026-08-21  
**Version:** v0.1 (FROZEN)  
**Type:** Contract / Decision  
**Status:** **ACCEPTED v0.1**  
**Implementation:** See [IA-002](./conference-edge-executor-admission-protection-ia-002.md) — **AUTHORIZED (bounded)**

**Parent track:** [conference-control-media-execution-isolation.md](./conference-control-media-execution-isolation.md)

**Prerequisites (field evidence):**

| Gate | Status | Evidence |
|------|--------|----------|
| P0.1g-A Control dispatch isolation | PASS | M02/M04 `MEMBER_ACCEPTED` + `SRD_DISPATCH` while M03 blocked |
| P0.1g-B Native SRD isolation | PASS | M03 `SRD_ENTER → no callback → SRD_TIMEOUT`; boundary proven |
| P0.1g-C Edge executor admission | FAIL | Session `52c8f036-…` · `logs/p01g2-edge-task-20260821-111634/` |

**Frozen RCA name:**

```text
Conference edge executor starvation by unbounded task
```

---

## Status

```text
P0.1g Acceptance 1          FAIL — HOLD
P0.1g-1 observation         PASS
P0.1g-2 observation         PASS
Executor starvation RCA     CLOSED
IA-002 field validation     PASS (session 034563a9-…)
This contract               ACCEPTED v0.1 (FROZEN)
Implementation              IA-002 COMPLETE (bounded — option A)
OPEN RCA                    ROOT CAUSE CANDIDATE FROZEN — RCA-B (M03 HIGH; M04 derived)
                              doc: conference-srd-post-admission-native-execution-failure-rca.md v0.1
                              obs: RCA-OBS-001 PASS — no further log expansion
                              next: RCA-B confirmation (SRD_NATIVE_CALL_ENTER/EXIT) NOT OPENED
```

**Next:**

```text
Conference SRD post-admission native execution failure  (new RCA — scoped)
        |
        v
P0.1g Acceptance 1 resume (only after native RCA bounded)
```

Option B (edge executor refactor) — **excluded**. Executor admission CLOSED.

---

## Contract questions (frozen)

### Q1 — Completion

> On a Conference **media edge executor**, may a **non-realization** task occupy the edge execution lane **without completion**?

**Answer:**

```text
NO
```

### Q2 — Admission

> May `MEDIA_CRITICAL` be denied **START** because a lower-category task holds the lane?

**Answer:**

```text
NO
```

A task that reaches `START` must eventually reach `END` **within category execution bounds** (see §3). Lane occupancy — not only presence of `END` — must remain controlled.

---

## 1. Executor role

### Name (logical)

```text
ConferenceMediaEdgeExecutor
```

Implementation today: `PeerMediaExecutors` keyed by `sessionId|remoteModuleId`.

### What it is

```text
edge-scoped execution lane
```

One serial lane per `(conferenceSession, remotePeer)` used to run PeerConnection-scoped JNI/work that must not block the coordinator.

### What it is not

```text
arbitrary JNI work queue
global WebRTC thread pool
coordinator offload sink
```

**Contract:** Tasks submitted to this lane inherit **edge media realization availability** obligations. Submitters must not treat the lane as an unbounded background worker.

---

## 2. Task taxonomy

### Category definitions (normative)

| Category | Definition |
|----------|------------|
| `MEDIA_CRITICAL` | **Establish or recover** an admitted media edge (realization gate) |
| `MEDIA_CONTROL` | **Bounded mutation** of an **existing** media edge; may influence media state |
| `OBSERVATION` | **No media state ownership**; read-only / telemetry-style edge work |

**MEDIA_CONTROL boundary:** `MEDIA_CONTROL` may influence media state but **must not indefinitely delay** `MEDIA_CRITICAL` realization. Workloads such as audio routing update, volume sync, codec renegotiation, or stats upload are **not** automatically `MEDIA_CONTROL`; each must map to a category by the definitions above. When in doubt, default toward `OBSERVATION` unless the work **mutates** admitted edge media state within a bounded window.

### Mapping (normative)

| Workload | Category | Notes |
|----------|----------|-------|
| `SRD_APPLY` (`applyRemoteAnswer` / conference accept media) | `MEDIA_CRITICAL` | Realization gate |
| ICE apply (`addIceCandidate`, ICE-restart offer path on edge) | `MEDIA_CONTROL` | Bounded mutation on existing PC |
| Playback enable (`setRemotePlaybackEnabled`) | `MEDIA_CONTROL` | Bounded mutation on existing PC |
| `refreshAudioLevel` / `getStats` | `OBSERVATION` | **Not** generic `OTHER` |
| Relay fanout (`__relay__` key) | Out of per-peer lane scope | Separate lane; not covered by per-edge starvation RCA |

**Contract:** `refreshAudioLevel` is **`OBSERVATION`**, not an unnamed `OTHER` task. Future observability must label it explicitly.

### Priority rule (normative, implementation-agnostic)

```text
MEDIA_CRITICAL  >  MEDIA_CONTROL  >  OBSERVATION
```

Specifically:

```text
SRD_APPLY
  priority over
AUDIO_LEVEL_REFRESH (refreshAudioLevel)
```

This contract states **what must be true**, not **how** to enforce it (no priority queue, preemption, or cancellation mandated here).

---

## 3. Lifecycle and execution bounds

### Lifecycle (mandatory)

Every task on a Conference media edge executor must follow:

```text
SUBMIT
  |
  v
START
  |
  v
END
```

### Completion

- `END` must occur for every `START`, with `success=true|false`.
- A task that `START`s and never `END`s (within process lifetime) is a **Task completion violation**.

### Execution bound (contract — not millisecond policy)

`END` alone is **not sufficient**. Lane occupancy must remain **bounded per category**.

| Category | Contract bound |
|----------|----------------|
| `MEDIA_CRITICAL` | Must **START** within the conference **realization window** for that edge |
| `MEDIA_CONTROL` | **Bounded execution**; must not hold the lane beyond what realization can tolerate |
| `OBSERVATION` | **Best effort**; **must not hold the lane** in a way that denies `MEDIA_CRITICAL` admission |

This document does **not** define millisecond budgets. It defines that **unbounded lane occupancy** — including `START … END` after an excessive delay — is a contract violation when it denies `MEDIA_CRITICAL` admission or realization.

**Example violation (informative):**

```text
refreshAudioLevel
  START
  ...
  END after 30s
```

Formally has `END`, but still violates **OBSERVATION** lane-occupancy and **MEDIA_CRITICAL** admission obligations if realization was blocked for that interval.

### Allowed exception

```text
executor process death
(application / VM teardown)
```

No other silent `START` without `END`, and no unbounded `START`→`END` occupancy, is acceptable.

### Current implementation gap (informative)

`PeerMediaExecutors` wraps work in `runCatching` and logs `EDGE_TASK_END` in `finally`. That satisfies the contract **only when the runnable returns within bounds**. A **blocking JNI call that never returns** produces:

```text
START
  |
  X
(no END)
```

That is a violation of this contract, regardless of whether a separate `SRD_TIMEOUT` watchdog fires elsewhere.

---

## 4. Observation task constraints

Normative rules for `OBSERVATION` tasks (including `refreshAudioLevel`):

1. **Non-owning workloads:** `OBSERVATION` tasks **must not become the sole holder** of edge execution availability. They carry **no media state ownership**.
2. **Must not** occupy an edge lane without eventual completion under normal process lifetime, **within OBSERVATION execution bounds**.
3. **Must not** deny `MEDIA_CRITICAL` tasks the ability to **START** within the realization window.
4. **Must not** change conference realization availability (admission facts remain on coordinator; observation must not substitute for realization success/failure).

**Architectural note:** The failure in session `52c8f036-…` is not merely "observation priority is low." A **non-owning** workload obtained **exclusive lane ownership** and denied `MEDIA_CRITICAL` admission. That is a **permissions / availability** violation, not only a priority ordering issue.

**Non-normative note:** Periodic observation (e.g. 100 ms `refreshConferenceAudioLevels`) on the **same** per-edge serial lane as `SRD_APPLY` creates starvation risk when observation JNI blocks. That risk is a contract violation when it occurs, not an acceptable tradeoff.

---

## 5. Failure attribution

**Media outcome** and **execution RCA** are **independent dimensions**. Both may appear in the same incident; neither replaces the other.

### Dimension 1 — Media fact (business / edge outcome)

Watchdog and media-edge state may still emit:

```text
MEDIA_EDGE_FAILED
reason=SRD_TIMEOUT
```

This records **what happened to the media edge** from the product's perspective. **Do not delete** `SRD_TIMEOUT` as a media fact when the watchdog fires.

### Dimension 2 — Execution RCA (engineering root cause)

When `MEDIA_CRITICAL` cannot **START** because the lane is held by an earlier task:

**Must attribute as:**

```text
EDGE_EXECUTOR_STARVATION
blockedByTask=<taskId>
blockedByCategory=<MEDIA_CRITICAL|MEDIA_CONTROL|OBSERVATION>
blockedByOrigin=<submitter identity>
```

Optional correlates (informative): `blockedSinceMs`, `submitSeq`, `startSeq`.

### Must not use as execution RCA

```text
SRD_TIMEOUT              (media fact only, when SRD never STARTed)
SRD native hang
sdpApplyMutex contention
coordinator block
cross-edge executor sharing
```

Those were investigated and closed for session `52c8f036-…`.

**Summary:**

```text
SRD_TIMEOUT           → media outcome (retain)
EDGE_EXECUTOR_STARVATION → execution RCA (when SRD never got START)
```

---

## 6. Relationship to P0.1g layers

| Layer | Question | Verdict |
|-------|----------|---------|
| P0.1g-A | Control dispatch isolation | PASS |
| P0.1g-B | Native SRD isolation (single edge) | PASS (M03 boundary) |
| P0.1g-C | Edge executor admission isolation | **FAIL** — this contract closes the gap |

P0.1g overall **Acceptance 1** remains **FAIL** until P0.1g-C obligations are satisfied under implementation authorization (separate decision).

---

## 7. Out of scope (explicit)

This contract does **not** authorize or specify:

```text
executor implementation changes
thread pool redesign
priority queue / preemption
task cancellation mechanism
JNI timeout policy
WebRTC getStats / SRD native behavior fixes
Recovery / Ready / Topology / Anchor changes
SRD watchdog budget changes
retry / UI workaround
```

Those require separate ADR or implementation authorization.

---

## Appendix A — Field evidence (informative)

**Session:** `52c8f036-e337-41bc-9f3e-a309992e4075`  
**Log dir:** `talkback/logs/p01g2-edge-task-20260821-111634/`  
**Authoritative run:** post force-stop restart

**Scope:** Sufficient for contract justification. No further field expansion required for v0.1.

### Observed chain (M01→M04)

```text
MEMBER_ACCEPTED
SRD_APPLY SUBMIT (taskId=107)
        |
        X   (no SRD_APPLY START within watchdog window)
        |
MEDIA_EDGE_FAILED / SRD_TIMEOUT   (media fact)
        |
        v
EDGE_EXECUTOR_STARVATION          (execution RCA)
```

SRD never entered WebRTC native path:

```text
SRD_DISPATCH
      |
      X
(no EXECUTOR_ENTER / no SRD_APPLY START)
```

### Blocking task

| Field | Value |
|-------|-------|
| `taskId` | `67` |
| Label at capture | `OTHER` |
| Code origin | `TalkbackCoordinator.refreshConferenceAudioLevels()` → `engine.refreshAudioLevel()` → `peerConnection.getStats` |
| Scheduler | 100 ms fixed rate on coordinator |
| `START` | `11:20:51.463` |
| `END` | **absent** (lane held ≥ 60 s; 670+ subsequent `SUBMIT`s queued) |

### Queue statistics (same session, edge M04)

```text
SUBMIT = 737
START  = 67
END    = 66
SRD_APPLY SUBMIT = 1
SRD_APPLY START  = 0
```

Proves: executor alive · not cross-edge shared · not creation failure · single-task poison · queue starvation.

### Classification

```text
Conference edge executor starvation by unbounded task
```

Poison task class: **`OBSERVATION`** (`refreshAudioLevel` / `getStats`), not `MEDIA_CRITICAL`.

---

## Appendix B — Decision log

| Decision | Resolution |
|----------|------------|
| May non-realization task block lane indefinitely? | **NO** |
| May `MEDIA_CRITICAL` be denied START by lower-category holder? | **NO** |
| Is `END` alone sufficient? | **NO** — category execution bounds apply |
| Is per-edge executor instance sharing the failure mode? | **NO** (cross-edge closed) |
| Is SRD mutex the failure mode when SRD never STARTs? | **NO** |
| Is `SRD_TIMEOUT` valid as media fact? | **YES** — independent of execution RCA |
| Primary contract gap | **Admission + completion + bounded occupancy on edge lane** |
| Implementation authorization | **IA-002 OPENED** — MEDIA_CRITICAL admission only (option A) |

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0 | Initial draft from P0.1g-2 field RCA |
| 2026-08-21 | v0.1 | Architecture review: title (admission + completion); MEDIA_CONTROL boundary; execution bounds; observation non-ownership; media fact vs execution RCA; status ACCEPTABLE |
| 2026-08-21 | v0.1 | **FROZEN** — ACCEPTED; IA-002 opened (option A) |
