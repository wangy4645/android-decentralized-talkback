# Implementation Authorization-001 — B2 Native Domain Gate

**ID:** conference-srd-native-domain-isolation-implementation-authorization-001  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Implementation Authorization  
**Status:** **AUTHORIZED** · **IA NOT OPENED** · **P0.1g NOT OPENED**

**Architecture:** B2-1 — Domain gate + serialized native realization queue ([B2 review](./conference-srd-native-resource-domain-isolation-b2-design-review.md))  
**Contract:** [PO-3](./conference-srd-native-domain-ownership-po-003.md) — **ACCEPTED**  
**Decision:** [v0.1.6](./conference-srd-native-completion-isolation-decision.md)  
**RCA:** [RCA-B CLOSED](./conference-srd-post-admission-native-execution-failure-rca.md)

---

## Goal (single sentence)

> Implement **B2-1** so one edge's native SRD non-return **cannot pollute** other edges' realization — per PO-3 failure containment and P0.1g Acceptance 1.

**Not:** fix M03 WebRTC hang · make M03 always succeed · reopen RCA-B.

---

## Scope boundary

This authorization **permits implementation** of the B2 domain gate.

Does **not** open:

```text
IA (field validation artifact)
P0.1g field rerun
B2-6 hard native cancel
B2-2 factory partition
Recovery / Ready / Topology / Anchor redesign
```

IA opens only after implementation is merge-ready and observability facts are present.

---

## Authorized mechanism (B2-1)

### AUTH-1 — Native domain gate before SRD-path JNI

All **conference `SRD_APPLY` / `applyRemoteAnswer`** native work must pass through `ConferenceNativeExecutionDomain` boundary:

```text
REQUEST_NATIVE_REALIZATION     (no JNI before this completes grant/deny)
        |
        +-- BUSY / DENIED -----> EDGE_LOCAL_FAILURE (non-blocking on edge executor)
        |
        v
DOMAIN_LEASE_GRANTED
        |
        v
NATIVE_DOMAIN_EXECUTION_ENTER
        |
        v
(all SRD-path JNI: negotiationSnapshot / signalingState / rollback JNI / setRemoteDescription)
        |
        v
NATIVE_DOMAIN_EXECUTION_EXIT
        |
        v
NATIVE_DOMAIN_RELEASE
```

**C5 (frozen):** edge executor thread must not synchronously block on shared factory-native domain without observable lease state.

### AUTH-2 — Single active realization slot

```text
One active native realization lease per process shared-factory domain
```

Scope: covers concurrent conference edges on M01 anchor sharing `WebRtcSharedFactory`.

Non-holders: **fast-fail** with `NATIVE_DOMAIN_LEASE_BUSY` — must not wait on peer edge JNI.

### AUTH-3 — `sdpApplyMutex` handling

```text
Retain sdpApplyMutex inside domain execution slot (nested)
Do NOT delete sdpApplyMutex in v1 without same-PC concurrent SRD safety proof
```

Domain gate is **outer** boundary; mutex is **inner** detail — not the cross-edge isolation surface.

### AUTH-4 — Slot recovery (M03 hang while holding lease)

When M03 native non-return is detected (existing watchdog / `SRD_NATIVE_CALL_ENTER` without `EXIT`):

```text
Mark M03 EDGE_LOCAL_FAILURE (contained)
Attempt domain lease release / slot invalidation for M03 holder
Do NOT block other edges waiting on invisible native lock
```

**AUTH-4 clarification (frozen):**

```text
Lease release / invalidation after native non-return
means logical containment only.

Implementation MUST NOT imply native execution termination.
```

Java `release()` or slot clear **does not** stop an in-flight native call. v1 uses lease **state**, not blind reuse:

```text
ACTIVE       — holder may execute under domain gate
STUCK        — holder native non-return observed; slot not reusable
QUARANTINED  — domain blocks new grants until policy clears (emit NATIVE_DOMAIN_QUARANTINED)
```

**Risk if ignored:** M03 native still running while domain thinks slot free → M04 enters native → overlapping unknown SRD state (worse than today).

**Commit 4 HOLD (2026-08-21):** `QUARANTINED` **must not** imply native resource availability. No new native lease until `SRD_NATIVE_CALL_EXIT` (or future B2-2 partition proof). Watchdog → `markStuck` → `quarantine` **without** slot reuse is design-only until architecture answers M04 independent native domain.

**B2-6 hard native cancel:** **NOT AUTHORIZED** in IA-001. Release is logical slot + terminal edge state only.

### AUTH-5 — `DOMAIN_BUSY` retry policy

```text
Same EDGE_TASK must not spin-wait on domain lease
Coordinator may submit a new SRD_APPLY EDGE_TASK after BUSY (new submitSeq)
No unbounded inline retry on edge executor thread
```

### AUTH-6 — Paths in / out of domain (v1)

| Path | v1 domain gate |
|------|----------------|
| Conference `applyRemoteAnswer` / `SRD_APPLY` | **IN — mandatory** |
| `createOffer` / `SLD` / `addIceCandidate` | **OUT** — unchanged unless proven to couple SRD_APPLY |
| P2P / non-conference WebRTC | **OUT** — do not refactor unrelated paths in v1 |

---

## Authorized observability (PO-3-C)

Minimum facts — extend existing `ConferenceSrdObservability` / SRD boundary logging:

```text
NATIVE_DOMAIN_LEASE_REQUEST   session edgeKey pcGeneration
NATIVE_DOMAIN_LEASE_GRANTED   holderEdgeKey
NATIVE_DOMAIN_LEASE_BUSY      holderEdgeKey waiterEdgeKey
NATIVE_DOMAIN_LEASE_DENIED    reason=
NATIVE_DOMAIN_EXECUTION_ENTER edgeKey
NATIVE_DOMAIN_EXECUTION_EXIT  edgeKey elapsedMs=
NATIVE_DOMAIN_RELEASE         edgeKey reason=
```

Retain for correlation:

```text
SRD_EXECUTOR_ENTER / SRD_ENTER / SRD_NATIVE_CALL_* / SRD_WATCHDOG_GAP
```

**Forbidden post-fix terminal shape:**

```text
SRD_TIMEOUT + lastEvent=NONE + unknown wait location
```

Watchdog / gap classification must include domain wait state when applicable.

---

## Authorized touch surfaces (illustrative — not normative class names)

```text
RealWebRtcAudioEngine.applyRemoteAnswer / awaitSetDescription path (conference)
TalkbackCoordinator.dispatchConferenceAcceptMedia (SRD_APPLY task body)
New: domain gate module (name at implementer discretion)
ConferenceSrdObservability (domain facts)
```

**Preserve unchanged:**

```text
IA-002 PeerMediaExecutors / MEDIA_CRITICAL admission
EDGE_TASK SUBMIT → START → END contract
Topology / Anchor / Recovery modules
WebRtcSharedFactory singleton + ADM (no factory partition)
```

---

## Explicitly not authorized

```text
SRD_TIMEOUT budget increase as primary fix
sdpApplyMutex delete
Per-edge mutex only (B2-4)
Factory-per-edge partition (B2-2)
Hard native cancel / PC dispose mid-SRD (B2-6)
ICE restart / signaling “fix M03”
Recovery / Ready / WiFi recovery main chain
Executor starvation rework
M04 independent RCA
RCA-B reopen
P0.1g field rerun (opens with IA only)
```

---

## Success criteria (implementation complete — pre-field)

| # | Criterion |
|---|-----------|
| 1 | No SRD-path JNI on conference `applyRemoteAnswer` before domain lease grant |
| 2 | Second edge gets `LEASE_BUSY` or proceeds — never invisible pre-mutex JNI stall |
| 3 | PO-3 fact vocabulary emitted on all SRD_APPLY paths |
| 4 | IA-002 MEDIA_CRITICAL START/END preserved |
| 5 | Unit/integration tests cover: holder blocks slot · waiter BUSY · M03 terminal releases slot for waiter |

**P0.1g Acceptance 1** adjudicated only under **IA** (separate gate).

---

## Field acceptance (deferred — IA gate)

Scenario: clean 4p · M01 anchor · M03 reproduces native non-return (same shape as session `5ce616de`).

| Edge | PASS |
|------|------|
| M03 | May `SRD_TIMEOUT` / `MEDIA_EDGE_FAILED` — **contained** |
| M04 | `NATIVE_DOMAIN_LEASE_*` then independent `SRD_ENTER` → `NATIVE_CALL_*` progress **or** observable `LEASE_BUSY` + retry — **not** `EXECUTOR_ENTER` → silent timeout |
| M02 | Reference pass |

**FAIL:** M04 pollution pattern from RCA-B field evidence.

---

## Proof obligation closure map

| Obligation | Closed when |
|------------|-------------|
| PO-3-A | Code review: single domain slot ownership |
| PO-3-B | P0.1g field PASS (IA) |
| PO-3-C | Domain facts in build under test |
| PO-3-D | This document authorized → IA separate |

---

## Implementation progress (IA-001)

```text
Commit 1  Domain abstraction (ConferenceNativeExecutionDomain)     PASS
Commit 2  SRD path gate (conference applyRemoteAnswer only)        PASS
Commit 3  Observability (domain facts + watchdog gap)              PASS
Commit 4  Failure containment (STUCK / QUARANTINED wiring)     **HOLD** — design review required

**Commit 4 hard invariant (frozen):**

```text
NATIVE_DOMAIN_QUARANTINED ≠ NATIVE_DOMAIN_AVAILABLE

No new native lease while holder native execution has not exited
(SRD_NATIVE_CALL_EXIT or proven independent native domain — B2-2)
```

**B2-1 status:** **PARTIAL** — gate + observability; does not alone satisfy P0.1g M04 independent realization while M03 native non-return.

**B2-2:** **ARCHITECTURE REVIEW OPEN** — [b2-2 partition review](./conference-srd-native-resource-domain-partition-b2-architecture-review.md). B2-1 retained as safety layer; P0.1g requires B2-2 proof.
```

---

```text
RCA-B                         CLOSED
PO-1 / PO-2 / PO-2b           COMPLETE
PO-3                          ACCEPTED
B2 design review              COMPLETE — B2-1 selected
B2-1 implementation           PARTIAL (Commit 1–3 PASS)
B2-2                          REVIEW OPEN
B2-2-A                        COMPLETE — FAIL — [audit](./conference-srd-b2-2a-native-parallel-domain-audit.md)
Model C/D spike               COMPLETE — [spike](./conference-srd-model-cd-partition-design-spike.md)
B2-2-D ADM audit              D-α lab **FAIL** — [adj](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)
Partition reassessment        OPEN — [reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md) v0.2
P0.1g                         HOLD — ORIGINAL CONTRACT
Implementation Authorization  AUTHORIZED — Commit 1–3 only
Commit 4                      HOLD
IA field (Commit 1–3)         OPEN for adjudication (P0.1g still HOLD)
P0.1g Acceptance 1            HOLD
```

---

## Next

1. ~~Architecture fork review~~ — [B2-2 review](./conference-srd-native-resource-domain-partition-b2-architecture-review.md)
2. ~~B2-2-A~~ — **FAIL** — [audit](./conference-srd-b2-2a-native-parallel-domain-audit.md)
3. ~~Model C/D design spike~~ — [spike](./conference-srd-model-cd-partition-design-spike.md)
4. ~~D-α lab spike~~ — **FAIL** — [adj](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)
5. **Conference Native Failure Containment Decision** — [decision](./conference-native-failure-containment-decision.md) Decision 1 OPEN
6. Commit 4 — **HOLD** · P0.1g **HOLD**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | IA-001 authorized; B2-1 scope; IA/P0.1g deferred |
| 2026-08-21 | v0.1.1 | AUTH-4 clarification — logical containment; STUCK/QUARANTINED lease states |
| 2026-08-21 | v0.1.2 | Commit 1–3 PASS; Commit 4 HOLD; B2-1 PARTIAL; B2-2 on table |
| 2026-08-21 | v0.1.3 | B2-2 architecture review opened |
| 2026-08-21 | v0.1.4 | B2-2-A FAIL — Model C/D next |
| 2026-08-21 | v0.1.5 | Model C/D spike complete; D-α leading; C rejected |
| 2026-08-21 | v0.1.6 | D-α lab spike authorized — LAB ONLY |
| 2026-08-21 | v0.1.7 | D-α lab FAIL — shared ADM 2-factory crash |
| 2026-08-21 | v0.1.8 | Partition HOLD; architecture reassessment opened |
