# GROUP → CONFERENCE Transition Contract Review

**ID:** group-conference-transition-contract-review  
**Date:** 2026-08-24  
**Version:** v0.1  
**Type:** Lifecycle Contract Refinement · Review  
**Status:** **ACCEPTED** (v0.1)  
**Implementation authorization:** **NONE**

This document is **not an ADR**. It adjudicates existing lifecycle relationships exposed by v6.1 field evidence. It does **not** redesign Conference topology, membership, Failure Domain taxonomy, or Factory partition.

---

## 1. Purpose

Determine whether `GROUP → CONFERENCE` (user action: start/join meeting while GROUP mesh is active) **must** treat completed GROUP native teardown as a prerequisite for:

1. Control-plane transition completion, and/or  
2. Conference media realization (SDP / invite / per-module PC bootstrap).

**Review question (single sentence):**

> Under current Factory / ADM constraints, which resources **must** serialize, and which control-plane lifecycles **may** proceed in parallel with background GROUP cleanup?

This review **does not** authorize code changes. A separate gate is required after decisions are accepted.

---

## 2. Frozen upstream (MUST NOT amend here)

| Contract | Role |
|----------|------|
| [ADR-0056](../adr/0056-conference-topology-first.md) | Topology · membership · anchor |
| [Failure Domain Contract](../analysis/conference-failure-domain-contract.md) | L1–L4 fault taxonomy |
| [Decision 1 — Option Z](../analysis/conference-native-failure-containment-decision.md) | Native isolation posture · CFC-1 |
| [Coordinator ↔ Media Execution Boundary](./coordinator-media-execution-boundary.md) | A0.1–A0.3 · async handoff · coordinator liveness |
| [Media Engine Ownership — Hangup Gate](./media-engine-ownership-hangup-gate.md) | Single owner per `moduleId` on factory entry |

**Explicitly out of scope for this review:**

```text
Factory redesign (D-β / D-γ)
Dual physical engines per moduleId
Negotiation / ICE / SRD / rollback redesign
New Failure Domain levels
Directed WiFi recovery / RNA / completion predicate changes
```

---

## 3. Field trigger (v6.1 — appendix only)

**Evidence:** `talkback/logs/meeting-end-liveness-v6.1-20260824-155623/` (M01 host, GROUP → meeting START)

| Run | D1+2 (leave → provision req) | T0 → first successful invite |
|-----|------------------------------|------------------------------|
| 1 | ~200 ms | ~5.6 s |
| 2 | ~120 ms | M02 ~5.6 s; M03 ~25 s (`releaseInFlight`) |

**Interpretation for this review (not a log replay):**

- GROUP PC close + factory release on the happy path is **fast** (tens of ms per module).  
- User-visible delay is **not** “hangup is slow”; it is **media realization blocked on scope-transition / releaseInFlight** plus **sync invite + retry loop**.  
- Control admission (`SESSION_CREATED`, `MEETING_START`) already **overlaps** GROUP release completion.  
- One module stuck in `releaseInFlight` correlated with delayed invites on **other** modules in Run 2 — consistent with **shared native/factory domain contention**, not proof of per-module runtime isolation.

Full T0–T5 line citations: Appendix A.

---

## 4. Observed START path (as-built, reference)

```text
JOIN_MEETING / SWITCH_TO_CONFERENCE
    ↓
leaveChannelSession(SWITCH_TO_CONFERENCE)
    ↓
GROUP hangup → releaseSessionMedia → mediaSessionClose (async)
    ↓
meshCallInternal(CONFERENCE)          ← does not wait for MEDIA_RELEASED
    ↓
SESSION_CREATED · MEETING_START
    ↓
Conference media realization (sync createOffer / invite today)
    ↓
mediaSessionReuse / mediaSessionProvision release cycle
    ↓
releaseInFlight · MEDIA_RELEASE_DEFERRED_CREATE
    ↓
sync throw · InviteDispatcher retry (3s × N)
```

**Not on START path:** `releaseConferenceChannelForGroupPtt()` — that is **MEETING_END → GROUP reconcile**, not meeting create.

**Diagnosis:** Case A is **not** enforced on control plane (partial Case C already). Case A **is** enforced on media plane via reuse/release coupling and synchronous realization.

---

## 5. Target dependency graph (normative intent of this review)

```text
USER / UI
   ↓
SWITCH_TO_CONFERENCE
   ↓
Control Admission                    (completion boundary — Q1)
   ├─ SESSION_CREATED
   └─ MEETING_START
          │
          ├──────────────────→ Conference Media Realization   (Q2 · Q4)
          │                         │
          │                         ├─ READY    → createOffer → invite
          │                         ├─ PENDING  → queue; resume on fact
          │                         └─ FAILED   → per-module terminal
          │
          └──────────────────→ GROUP Media Cleanup            (independent)
                                    │
                                    └─ background lifecycle
                                       (must not block control admission)
```

### Forbidden coupling (MUST NOT remain normative)

```text
GROUP MEDIA_RELEASED  ==  SWITCH_TO_CONFERENCE control-plane completion     ✗

GROUP MEDIA_RELEASED  ==  Meeting media-usable / invite-complete             ✗

GROUP releaseInFlight
      ↓
sync createOffer / invite
      ↓
InviteDispatcher retry until timeout                           ✗
```

### Target control model (Q4 — logical per-module, not runtime isolation)

```text
M02 → READY    → INVITE (when acquisition path allows)
M03 → PENDING  → later READY or FAILED (must not sync-block M02 control/realization decision)
M04 → FAILED   → attributed locally; no global retry storm
```

`M03 → PENDING` is a **module-local PENDING** example (own lifecycle / provisioning not yet complete). Peer lease contention is represented as `DOMAIN_BLOCKED`, not `PENDING`.

**Clarification:** This is the **target control-plane adjudication model**. It does **not** claim P0.1g-style per-module runtime isolation. Impact from a peer-held runtime lease MUST be explicit and attributed as `DOMAIN_BLOCKED`; it MUST NOT be implemented as GCT `PENDING`, synchronous waiting, or global retry serialization.

---

## 6. Ownership graph (review scope)

Review **does not** assume two physical engines per `moduleId`. Under current `ModuleMediaEngineFactory` (one mesh entry per `moduleId`), adjudicate **logical** ownership during transition:

```text
moduleId
   │
   ├── Factory entry (process-wide mesh slot)
   │      └── at most ONE lifecycle mutator at a time
   │          (hangup-gate §2 — FROZEN)
   │
   ├── GROUP scope session state
   │      └── GROUP **logical scope owner** (control + media facts)
   │
   └── CONFERENCE scope session state
          └── CONFERENCE **logical scope owner** (control + media facts)
```

**Scope ownership ≠ factory runtime mutator.** GROUP and CONFERENCE scope owners are **logical** lifecycle owners. Scope ownership does **not** grant concurrent mutation rights over the same factory entry. Physical factory-entry mutation remains governed by the single-owner execution boundary (hangup-gate §2).

```text
scope owner  ≠  factory runtime mutator
```

### Serialization vs parallelism (decisions required)

| Resource / concern | Must serialize? | May parallel with control admission? | Notes |
|--------------------|-----------------|--------------------------------------|-------|
| Factory entry mutation (`release` / `getOrCreate`) | **Yes** — one mutator | Cleanup **execution** may run async while control proceeds | Ownership gate remains; not removed |
| GROUP PC teardown | GROUP owner | **Yes** — async facts | Does not gate `MEETING_START` |
| CONFERENCE PC provision | CONFERENCE owner | **Yes** — submit PENDING, complete on fact | Must not sync-wait on coordinator |
| SDP / invite for module Mx | Per-module **decision** | **Independent control adjudication** | Shared runtime may affect outcome; must not sync-serialize globally (Q4) |
| `releaseConferenceChannelForGroupPtt` | N/A on START | — | END path only |

**Key distinction:**

```text
Parallel  ≠  concurrent factory mutation on same moduleId
Parallel  =  control transition live while cleanup/realization complete asynchronously
```

---

## 7. Four-question adjudication table

**Adjudication (2026-08-24):** Q1 **ACCEPT** · Q2 **ACCEPT** · Q3 **ACCEPT** · Q4 **ACCEPT WITH CLARIFICATION** · INV-T1..T5 **ACCEPT**

| # | Question | Decision | Rationale (v6.1) |
|---|----------|----------|------------------|
| **Q1** | What completes `SWITCH_TO_CONFERENCE` on the control plane? | **`SWITCH_TO_CONFERENCE` control-plane completion** = `SESSION_CREATED` + `MEETING_START` transition admitted. **It MUST NOT wait for:** GROUP `MEDIA_RELEASED`, GROUP channel idle, or Conference media realization completion. This is **not** “meeting media-usable” or user-action complete. | Session created ~50 ms **before** GROUP release facts; control already decoupled from cleanup. |
| **Q2** | What are valid outcomes of Conference media acquisition? | **`READY \| PENDING \| FAILED` per module.** Forbidden: synchronous throw from coordinator into native create; forbidden: InviteDispatcher retry loop as substitute for PENDING. | Sync throw + retry caused 5–25 s delay despite fast teardown. |
| **Q3** | Who owns what during transition? | See §6. Factory entry: single mutator via ownership gate. Scope owners are **logical**; handoff via **facts**, not sync completion polling. | Aligns with frozen hangup-gate invariant without extending Case A to control plane. |
| **Q4** | When may realization defer? | Conference realization **MUST distinguish two non-interchangeable outcomes.** (1) Module-local lifecycle not yet complete → GCT `PENDING`; resume only on the module's own lifecycle / provisioning fact. (2) Admission blocked by another holder's runtime lease → Failure Domain `DOMAIN_BLOCKED`; attribution MUST identify the holder/cause edge and runtime domain. `PENDING` MUST NOT wait for another edge's retained or unknown native lease, and MUST NOT become an implicit wait for peer JNI completion. A peer `RETAINED` / `UNKNOWN` lease is therefore not a GCT `PENDING` condition; it is an explicit L2 `DOMAIN_BLOCKED` condition. A module in `PENDING` or `FAILED` **MUST NOT synchronously block** coordinator liveness, unrelated module control-plane progression, or unrelated module realization **decision**. Neither `PENDING` nor `DOMAIN_BLOCKED`, by itself, declares the Conference unavailable. | v6 field evidence distinguishes module-local pending acquisition from peer-holder contention. M02 was denied admission because M03 retained the shared-factory lease; this is `DOMAIN_BLOCKED`, not GCT `PENDING`. |

---

## 8. R5 relationship check

v6.1 field + [Coordinator ↔ Media Execution Boundary](./coordinator-media-execution-boundary.md) §3 exposed: async release queue + sync `create()` on coordinator.

**Transition contract MUST NOT reintroduce:**

```text
PENDING
   ↓
coordinator sync wait / sync native create / sync factory.getOrCreate
   ↓
blocked coordinator turn
```

**Permitted shape (consistent with execution boundary §3–§4):**

```text
requestEngine(CONFERENCE, onReady, onFailed?)
   ↓
PENDING recorded (fact or internal queue state)
   ↓
(separate turn / async completion)
   ↓
MEDIA_PROVISIONED → onReady → createOffer → invite
```

Local prototype (`engine-ready continuation`) is **candidate mechanism**, not approved implementation. This review names **`PENDING → READY|FAILED`** as the contract surface for implementation authorization.

---

## 9. Normative invariants (ACCEPTED)

The following are binding refinements atop the execution boundary (implementation still requires separate authorization):

```text
INV-T1  SWITCH_TO_CONFERENCE control-plane completion
        MUST NOT require GROUP native cleanup completion,
        GROUP channel idle, or Conference media realization completion.

INV-T2  Conference media realization
        MUST be independently representable per module as
        READY | PENDING | FAILED.

INV-T3  A per-module media realization outcome MUST NOT synchronously block
        unrelated module realization or control-plane transition liveness.
        Shared runtime-domain contention MAY affect multiple module outcomes,
        but the impact MUST be explicit and attributed; it MUST NOT create
        synchronous global serialization or retry-loop behavior.

INV-T4  Control-plane transition (SESSION_CREATED · MEETING_START)
        MUST remain live regardless of native cleanup latency
        (FAILED/DEGRADED facts allowed; silent stall forbidden).

INV-T5  PENDING MUST NOT collapse into coordinator synchronous
        native/runtime invocation (R5 regression guard).
```

---

## 10. Explicit stop list (post-review posture)

Until transition contract is implemented under a new authorization, **do not expand**:

```text
A0.8 / A0.9 release-barrier strengthening on START path
Additional hangup watchdog / barrier layers for GROUP→CONFERENCE
InviteDispatcher retry as primary PENDING mechanism
Treating leaveChannelSession hangup as product semantics
Factory / D-β / D-γ redesign disguised as transition fix
```

**May retain (frozen / in-flight):**

```text
A0.1 · A0.4 · A0.5b · A0.6 · A0.7a
MEETING_END hangup ordering (hangup-gate §5.1 — END path, not START)
Engine-ready continuation — authorized per [implementation authorization](./group-conference-transition-implementation-authorization.md)
```

---

## 11. Review exit criteria

**Status: CLOSED (ACCEPTED v0.1, 2026-08-24)**

1. §7 four questions — **ACCEPTED** (Q4 with clarification).  
2. §9 invariants INV-T1..T5 — **ACCEPTED** as refinements to execution boundary.  
3. §6 ownership table — **ACCEPTED** under current Factory constraint (no dual engine).  
4. Implementation — **deferred**; separate authorization required.

**Success criterion met:** writable contract forbidding the v6.1 failure mode class without new ADR. Case A (`GROUP cleanup complete → Conference starts`) is **no longer target architecture**.

---

## Appendix A — v6.1 START timing (M01, evidence only)

Log: `talkback/logs/meeting-end-liveness-v6.1-20260824-155623/m01-logcat.txt`

### Run 1 (2026-08-24 15:57:13)

| Marker | Timestamp | Event |
|--------|-----------|-------|
| T0 | 15:57:13.853 | `JOIN_MEETING_TRACE phase=intent` |
| T1 | 15:57:13.865 | `CONTROL_LEAVE_SIGNAL_DISPATCHED` |
| T2 | 15:57:13.947 | `MEDIA_RELEASE_SCHEDULED` `mediaSessionClose` |
| — | 15:57:13.983 | `SESSION_CREATED` (before GROUP `MEDIA_RELEASED`) |
| — | 15:57:14.022 | GROUP `MEDIA_RELEASED` (all modules) |
| T3 | 15:57:14.061 | `MEDIA_PROVISION_REQUESTED` CONFERENCE |
| T4 | 15:57:14.106 | `MEDIA_LIFECYCLE` CONFERENCE BOOTSTRAPPING |
| — | 15:57:14.043 | `Conference invite SDP failed` pending async release |
| T5 | 15:57:19.503 | `INVITE_DISPATCH_COMPLETED` SUCCESS 3/3 |

### Run 2 (2026-08-24 15:57:26)

| Marker | Timestamp | Event |
|--------|-----------|-------|
| T0 | 15:57:26.952 | intent |
| T1 | 15:57:26.960 | CONTROL_LEAVE |
| T2 | 15:57:27.023 | MEDIA_RELEASE_SCHEDULED close |
| T5 (M02) | 15:57:32.601 | first `Conference invite sent` |
| — | 15:57:32.703 | `MEDIA_PROVISION_REQUESTED` M03 `origin=releaseInFlight` |
| T5 (M03) | 15:57:52.578 | M03 invite (~25 s from T0) |

---

## Appendix B — Related code touchpoints (inventory, not authorization)

| Area | Role today | Review concern |
|------|------------|----------------|
| `TalkViewModel.joinMeeting` | `leaveChannelSession(SWITCH_TO_CONFERENCE)` | Control vs product semantics |
| `TalkbackCoordinator.meshCallInternal` | `endConflictingMeshSessions`, optional barrier | START teardown coupling |
| `MediaSessionManager.requestEngine` | reuse / releaseInFlight queue | Q2 PENDING surface |
| `InviteDispatcher` | retry on sync failure | Forbidden as PENDING substitute |
| `releaseConferenceChannelForGroupPtt` | MEETING_END only | Do not conflate with START |

---

## Revision history

| Version | Date | Change |
|---------|------|--------|
| v0.1 | 2026-08-24 | Initial REVIEW draft; v6.1 adjudication |
| v0.1 ACCEPTED | 2026-08-24 | Q1 control-plane completion wording; Q4/INV-T3 shared-domain clarification; §6 scope-owner note; review closed; **no implementation authorization** |
| v0.1 Q4 splice | 2026-08-25 | Q4 **ACCEPT WITH CLARIFICATION** unchanged; Decision/Rationale distinguish GCT `PENDING` vs L2 `DOMAIN_BLOCKED`; §5 module-local PENDING example; **no new contract · no IA** |
