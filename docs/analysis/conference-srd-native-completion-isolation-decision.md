# Conference SRD Native Completion Isolation — Architecture Decision

**ID:** conference-srd-native-completion-isolation-decision  
**Date:** 2026-08-21  
**Version:** v0.1.8  
**Type:** Architecture Decision (pre-implementation)  
**Status:** **B2 COMPLETE** · PO-3 **ACCEPTED** · **Implementation AUTHORIZED** · **IA NOT OPENED**

**Parent:** [Design Options v0](./conference-srd-native-completion-isolation-design-options.md)  
**RCA:** [conference-srd-post-admission-native-execution-failure-rca.md](./conference-srd-post-admission-native-execution-failure-rca.md) (v0.2 · RCA-B **CLOSED**)

---

## Problem (frozen)

Not:

```text
SRD success rate is low
```

Is:

```text
one edge native failure
        |
        v
shared resource ownership leakage
        |
        v
other edge realization blocked
```

**Design goal:**

> Limit failure blast radius to the failing edge.

Pass means:

```text
M03 may SRD_TIMEOUT
        AND
M04 makes independent SRD progress (or fails on own edge only)
```

Not: `M03 never timeout`.

---

## Architecture review verdict (A–D)

| Option | Verdict | Role |
|--------|---------|------|
| **A** Narrow mutex scope | **Insufficient** as primary | Sub-optimization under B only; block is **inside** native call while mutex held |
| **B** Per-edge native apply isolation | **Insufficient alone** | Mutex-only; PO-2b: M04 blocked pre-mutex |
| **B2** Native resource domain isolation | **Primary** | Protects shared native SRD realization domain |
| **C** Native SRD domain ownership contract | **Companion** | Domain lease / entry gate; not edge-only ownership |
| **D** Native hard watchdog / cancel | **Not first fix** | Last-resort blast-radius bound; high WebRTC partial-state risk |

**Ranking (post PO-2b):** B2 > C > A > D

**Do not** select B (per-edge mutex only) or A/D as primary fix direction.

---

## Selected direction (architecture — frozen post PO-2b)

```text
Primary:   B2 — Native resource domain isolation
Companion: C — Native SRD domain ownership contract (PO-3)
```

**Combined target:**

```text
ConferenceNativeExecutionDomain (owner)
        |
        v
Domain lease / entry gate (C)
        |
        v
Edge requests native realization — no direct black-hole JNI
        |
        v
M03 hang contained; M04 gets DOMAIN_BUSY or edge-local failure — not silent cross-edge stall
```

**B alone refuted (PO-2b):** M04 stalls on `signalingState()` JNI before `sdpApplyMutex`.  
**C alone insufficient:** lease without domain partition does not bound native hang.

---

## Proof obligations (must close before IA)

### PO-1 — Native ownership inventory — **COMPLETE (BLOCKED)**

**Artifact:** [conference-srd-native-ownership-inventory-po-001.md](./conference-srd-native-ownership-inventory-po-001.md)

**Verdict:** dependency set **≠** `{sdpApplyMutex only}`. Also shared: `PeerConnectionFactory`, `JavaAudioDeviceModule`, `SharedLocalAudio` track; native signaling worker **PO-2 unknown**.

**Implication:** B remains primary direction; PO-2 scope **must** address full inventory (not mutex-only patch).

### PO-2 — Isolation boundary definition — **COMPLETE**

**Artifact:** [conference-srd-native-isolation-boundary-po-002.md](./conference-srd-native-isolation-boundary-po-002.md)

**Implication:** PO-2b **COMPLETE** — B insufficient; **B2 selected**. See [po-2b](./conference-srd-native-worker-characterization-po-2b.md).

### PO-3 — Native SRD domain ownership contract — **ACCEPTED**

**Artifact:** [conference-srd-native-domain-ownership-po-003.md](./conference-srd-native-domain-ownership-po-003.md)  
**B2 review:** [conference-srd-native-resource-domain-isolation-b2-design-review.md](./conference-srd-native-resource-domain-isolation-b2-design-review.md)

### B2 design decision review — **COMPLETE**

**Selected mechanism:** B2-1 domain gate + serialized realization queue; B2-3 non-blocking edge shape.  
**Rejected primary:** B2-4 per-edge mutex, B2-5 narrow mutex, B2-2 factory partition.

**Next:** Implementation per [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md) — IA **NOT OPENED**

Must not collide with:

```text
IA-002 MEDIA_CRITICAL admission
EDGE_TASK SUBMIT → START → END
Topology / Anchor authority
```

### PO-4 — Invariants under M03 native hang

Prove or disprove:

```text
M03: SRD_NATIVE_CALL_ENTER without EXIT
M04: independent SRD path to NATIVE_CALL_EXIT (or own-edge terminal failure)
No implicit wait on M03 mutex / owner / factory thread
```

---

## Prohibited (this track)

```text
SRD_TIMEOUT budget stretch as primary fix
sdpApplyMutex delete without PO-1 + PO-2 proof
WebRTC / ICE / signaling “fix M03 hang” scope
Recovery / Ready / Topology / Anchor reopen
Executor starvation rework (CLOSED)
RCA-B reopen without new field evidence
M04 independent RCA without M04 NATIVE_CALL_ENTER missing EXIT
Implementation / PR before IA
Option D as first shipped fix
```

---

## Field acceptance (P0.1g resume gate)

Re-run **only after** IA + implementation. Scenario aligned with RCA-B field shape:

**Setup:** clean 4p · M01 anchor · same conference generation · M03 reproduces native non-return.

**Expected:**

| Edge | Expected |
|------|----------|
| M03 | `SRD_NATIVE_CALL_ENTER` · no `SRD_NATIVE_CALL_EXIT` · `MEDIA_EDGE_FAILED` / `SRD_TIMEOUT` **allowed** |
| M04 | `SRD_ENTER` → `SRD_NATIVE_CALL_ENTER` → `SRD_NATIVE_CALL_EXIT` → `SRD_EXIT` success (or fail on **own** edge only — not mutex-coupled timeout) |
| M02 | Reference pass (unchanged) |

**P0.1g Acceptance 1:** PASS only if M03 fails **and** M04 (and M02) show **independent** realization progress per table above.

**FAIL if:** M04 blocked by M03 hang (current failure mode).

Observability: retain `SRD_NATIVE_CALL_*`, `SRD_WATCHDOG_GAP`, ownership facts from C.

---

## Posture

```text
RCA-B                   CLOSED
Fix Design Review       B2-1 PARTIAL · B2-2 review OPEN
PO-3 (C contract)       ACCEPTED
B2-1 (Commit 1–3)     PASS — [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)
Commit 4                HOLD
B2-2 partition          HOLD — [reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md)
Model C/D               COMPLETE — [spike](./conference-srd-model-cd-partition-design-spike.md) · C rejected · D-α FAIL
D-α lab spike           COMPLETE — FAIL — [adj](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)
D-β / D-γ               ON TABLE — NOT AUTHORIZED
Single native domain
+ upper-layer isolation ON TABLE
B2-1                    RETAIN
Production              NOT AUTHORIZED
Commit 4                HOLD
P0.1g Acceptance 1      HOLD — ORIGINAL CONTRACT
Implementation Auth     Commit 1–3 only
IA field (1–3)          ALLOWABLE
```

---

## Next

1. ~~PO-1~~ — [po-001](./conference-srd-native-ownership-inventory-po-001.md)
2. ~~PO-2~~ — [po-002](./conference-srd-native-isolation-boundary-po-002.md)
3. ~~PO-2b~~ — B2 fork accepted
4. ~~PO-3~~ — accepted
5. ~~B2 design review~~ — B2-1 selected
6. ~~Implementation Authorization~~ — Commit 1–3 ([IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md))
7. ~~B2-2-A~~ — **FAIL** — [audit](./conference-srd-b2-2a-native-parallel-domain-audit.md)
8. ~~Model C/D spike~~ — C rejected; D-α leading — [spike](./conference-srd-model-cd-partition-design-spike.md)
9. ~~D-α lab~~ — **FAIL**
10. Conference Native Failure Containment Decision — [decision](./conference-native-failure-containment-decision.md) · Decision 1 OPEN · P0.1g **HOLD — ORIGINAL CONTRACT**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Architecture direction B+C accepted; A insufficient; D deferred; proof obligations + field acceptance defined |
| 2026-08-21 | v0.1.1 | PO-1 complete BLOCKED — linked po-001 inventory |
| 2026-08-21 | v0.1.2 | PO-2 complete — linked po-002 boundaries; C waiting |
| 2026-08-21 | v0.1.3 | PO-2b complete — B insufficient; B2 fork |
| 2026-08-21 | v0.1.4 | B2 SELECTED; PO-3 OPEN (domain ownership contract) |
| 2026-08-21 | v0.1.5 | PO-3 artifact linked; B2 design review next |
| 2026-08-21 | v0.1.6 | B2 review complete; B2-1 selected; PO-3 accepted |
| 2026-08-21 | v0.1.7 | Implementation Authorization IA-001 opened |
| 2026-08-21 | v0.1.8 | B2-1 partial; B2-2 partition review opened; Commit 4 HOLD |
