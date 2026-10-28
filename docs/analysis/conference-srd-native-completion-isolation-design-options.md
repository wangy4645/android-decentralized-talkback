# Conference SRD Native Completion Isolation — Design Options

**ID:** conference-srd-native-completion-isolation-design-options  
**Date:** 2026-08-21  
**Version:** v0.1 (architecture review complete)  
**Type:** Fix Design Review  
**Status:** **REVIEW COMPLETE** — B2-1 → [B2 review](./conference-srd-native-resource-domain-isolation-b2-design-review.md) · **Implementation NOT AUTHORIZED**

**Parent RCA:** [conference-srd-post-admission-native-execution-failure-rca.md](./conference-srd-post-admission-native-execution-failure-rca.md) (v0.2 · RCA-B **CLOSED**)  
**Evidence:** [rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt](../../logs/rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt) · session `5ce616de-…`

---

## Question (single)

> How can **one edge's** native SRD non-return **not block** other edges' media realization?

**Not this document's job:**

```text
How to fix WebRTC / make M03 SRD always succeed
How to tune ICE / signaling / Recovery / Anchor / Topology
```

**Fix goal (isolation, not success):**

```text
One edge native SRD failure
        |
        must not block
        |
other edge realization
```

Aligns with **P0.1g Acceptance 1** (cross-edge pollution), which remains **HOLD** until fix authorization + validation.

---

## Frozen RCA attribution

```text
Conference SRD native completion non-return under mutex

M03 (primary):
  SRD_APPLY START
      → sdpApplyMutex ACQUIRED
      → SRD_NATIVE_CALL_ENTER
      → (setRemoteDescription never returns)
      → watchdog → SRD_TIMEOUT

M04 (derived / coupled):
  M03 holds sdpApplyMutex → M04 SRD path blocked → SRD_TIMEOUT
```

**Not reopened:**

| Hypothesis | Status |
|------------|--------|
| Executor starvation | CLOSED (IA-002) |
| Callback delivery failure | CLOSED / NOT HIT |
| Java latch ordering | CLOSED / NOT HIT |
| Signaling ordering | CLOSED |
| Timeout budget too short | CLOSED (B3 not hit) |
| Cross-edge executor contamination | CLOSED |

**M04 rule:** do **not** open `RCA-M04-native-failure` unless M04 independently shows `SRD_NATIVE_CALL_ENTER` without `EXIT` on its own edge.

**Failure locus:**

```text
PeerConnection native operation lifecycle
```

Not: Conference topology · Anchor · Recovery · Executor admission.

---

## Design review constraints (frozen for this track)

### C1 — Do not regress frozen contracts

Must preserve:

```text
MEDIA_CRITICAL admission (IA-002 / edge executor contract v0.1)
EDGE_TASK lifecycle (SUBMIT → START → END)
Topology authority / Anchor semantics
```

### C2 — Do not “fix” via watchdog stretch

Reject as primary strategy:

```text
SRD_TIMEOUT 3s → 10s
```

Changes symptom timing only; does not change failure mode or cross-edge isolation.

### C3 — Do not delete sdpApplyMutex without proof

`sdpApplyMutex` exists for concurrent SRD safety on shared factory/PC semantics. Removal requires explicit proof of:

```text
Same-PC concurrent SRD safety
Callback ordering under overlap
Signaling state transition invariants
```

### C4 — Success criterion = isolation

Pass is **not** “M03 always completes SRD”. Pass is:

```text
M03 may still SRD_TIMEOUT
        AND
M02 / M04 complete realization (or fail on own edge only)
```

---

## Option space (no selection)

| Option | Direction | Addresses | Primary risk |
|--------|-----------|-----------|--------------|
| **A** | Narrow mutex hold scope | Reduce cross-edge blocking window | May weaken concurrent SRD protection if scope wrong |
| **B** | Per-edge native apply isolation | Mutex / executor bound to edge PC lifecycle | Threading + state complexity; factory sharing |
| **C** | SRD operation ownership contract | Explicit owner + queue for native apply on shared resources | State machine + admission interaction |
| **D** | Native call cancellation / hard watchdog | Bound stuck native call; release mutex | WebRTC cancel semantics; partial apply state |

---

### Option A — Narrow mutex hold scope

**Idea:** Hold `sdpApplyMutex` only around the minimal native entry/exit, or split “wait for callback” outside mutex (if provably safe).

**Pros:** Smallest structural change if callback wait is already outside mutex today for some paths.  
**Cons:** If native call itself hangs **inside** mutex, narrowing wait scope alone does not fix M03; must prove what remains inside lock.  
**P0.1g fit:** Only helps if M04 blocked solely by mutex held during **non-native** work — field evidence shows M03 stuck **inside** native ENTER.

---

### Option B — Per-edge native apply isolation

**Idea:** Serialize or isolate native SRD per edge (per-PC or per-edge lane) so M03 hang does not hold the global `sdpApplyMutex` that M04 needs.

**Pros:** Directly targets observed coupling (M03 → M04).  
**Cons:** Shared `PeerConnectionFactory` / track attachment may still imply hidden sharing; needs inventory of all global locks.  
**P0.1g fit:** Strong candidate **if** global mutex is the coupling mechanism — must be validated in design proof, not assumed.

---

### Option C — SRD operation ownership contract

**Idea:** Named owner for “native apply generation” per conference edge; competing edges queue or fail fast with explicit `OWNERSHIP` facts instead of implicit mutex blocking.

**Pros:** Observable; aligns with existing EDGE_TASK / executor vocabulary.  
**Cons:** New contract surface; must not collide with IA-002 MEDIA_CRITICAL START guarantees.  
**P0.1g fit:** Good for **admission + isolation** story; does not by itself fix M03 native hang.

---

### Option D — Native call cancellation / hard watchdog

**Idea:** After bounded time, abandon native wait, release mutex, surface terminal edge failure without poisoning other edges.

**Pros:** Bounds blast radius even if root native bug persists.  
**Cons:** WebRTC may not support safe cancel mid-`setRemoteDescription`; risk of PC corruption / zombie state.  
**P0.1g fit:** Isolation-oriented, but highest semantic risk — needs WebRTC lifecycle audit before any authorization.

---

## Evaluation matrix (for later decision — not scored here)

Each chosen option must answer:

```text
1. M03 native non-return still possible?          (allowed — isolation fix)
2. M04 can complete SRD while M03 stuck?        (required for P0.1g PASS)
3. IA-002 MEDIA_CRITICAL START preserved?         (required)
4. EDGE_TASK END / failure path explicit?         (required)
5. Mutex / PC invariants documented?            (required)
6. Field adjudication scenario reproducible?    (required — clean 4p, M01 anchor)
```

---

## Explicitly out of scope (this review)

```text
Recovery / Ready / WiFi recovery main chain
Anchor / topology redesign
Executor starvation rework (CLOSED)
RCA-B re-open without new field evidence
M04 independent RCA without NATIVE_CALL_ENTER on M04 edge
Implementation / PR / timeout-only patch
```

---

## Architecture review summary (2026-08-21)

| Rank | Option | Verdict |
|------|--------|---------|
| 1 | **B** Per-edge native apply isolation | Primary |
| 2 | **C** SRD operation ownership contract | Companion to B |
| 3 | **A** Narrow mutex scope | Insufficient alone |
| 4 | **D** Native hard watchdog | Not first fix |

**Selected direction (architecture):** **B + C** — see [decision doc](./conference-srd-native-completion-isolation-decision.md).

**Prerequisite before IA:** Native ownership inventory (PO-1); do not assume `sdpApplyMutex` is the only shared resource.

---

## Posture

```text
IA-002                  PASS
RCA-OBS-001             PASS
RCA-B                   CLOSED
P0.1g Acceptance 1      HOLD
Fix Design Review       COMPLETE — B2-1
PO-3 (C)                ACCEPTED
B2 design review        COMPLETE — B2-1
B2-2 partition review   OPEN
Commit 4                HOLD
Implementation Auth     Commit 1–3 only
IA                      NOT OPENED
```

---

## Next (after review)

1. ~~Architecture review of options A–D~~ — **done** → [Decision v0.1](./conference-srd-native-completion-isolation-decision.md)
2. Close proof obligations PO-1 … PO-4 (inventory first)
3. IA artifact — **NOT OPENED**
4. Implementation Authorization — **NOT AUTHORIZED**
5. P0.1g Acceptance 1 field re-run only after authorized fix

**Do not** treat RCA-B CLOSED as P0.1g unblock.

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0 | OPEN — design options from RCA-B CLOSED; no selection |
| 2026-08-21 | v0.1 | Architecture review; B+C direction; linked decision doc |
| 2026-08-21 | v0.1.1 | B2 review complete; B2-1 selected; linked b2-review |
