# ADR-0058 Profile 03 Amendment — Transport Resilience (Outline)

## Status

```text
DRAFT — AMENDMENT OUTLINE
ADJUDICATION COMPLETE (R1–R10 ACCEPTED)
IMPLEMENTATION ACCEPTANCE PENDING (C1–C10 + X1–X3)
Parent:     ADR-0060 ACCEPTED — FROZEN
            Profile 02 resilience amendment ADJUDICATION COMPLETE (A1–A10)
Base:       0058-implementation-profile-03-media-runtime.md (pre-amendment CLOSED)
Prerequisite: Profile 02 defines legal wire execution; this amendment does NOT reopen P02
Scope:      liveness observation → publication → sender mode → Profile 02 execution
NOT IN SCOPE: C1–C8 hex corpus implementation; E3a field runs; timer numerics (until R2/R3)
Media Engine: NOT AUTHORIZED
```

---

## Problem statement

Profile 02 answers: **how may one media unit legally traverse multicast / unicast fan-out?**

Profile 03 resilience amendment answers:

```text
How does a receiver judge multicast is still alive?
How is that fact published?
How does a sender safely choose PerReceiverTransportMode?
How does Profile 02 execution get invoked without semantic pollution?
```

---

## Frozen authority stack (ADR-0060 — not reopened)

```text
Receiver
  observes actual multicast receive behavior
       ↓
MulticastReceiveLiveness
  ALIVE | SUSPECT | DEAD
       ↓ publication (freshness identity — R4)
Sender
  consumes fresh receiver fact
       ↓
PerReceiverTransportMode
  MULTICAST | UNICAST_FALLBACK
       ↓
Profile 02 delivery execution
```

```text
receiver owns observation
sender owns delivery-mode execution
```

---

## Semantic pollution — FORBIDDEN in Profile 03

```text
sender infers receiver multicast ALIVE from sendto success, IGMP, MulticastLock, AP state   REJECT
receiver directly commands sender "switch to unicast" (imperative control)                   REJECT
single packet arrival = ALIVE                                                                REJECT
capability / liveness change mutates Membership generation or conferenceEpoch                REJECT
MulticastReceiveLiveness publication treated as Profile 01 Membership Fact                   REJECT
MulticastReceiveLiveness = Source Health / Membership Health / Conference readiness          REJECT
```

```text
MulticastReceiveLiveness is transport optimization eligibility observation,
not media quality, not membership truth, not conference degraded state.
```

DEAD → UNICAST_FALLBACK for one receiver MUST NOT imply Conference failure for others.

---

## Amendment boundary

**IN SCOPE:**

```text
receiver-local multicast ingress observation ownership
ALIVE / SUSPECT / DEAD semantics
evidence inputs and hysteresis policy (structure; numerics deferred)
publication contract + stale rejection
sender PerReceiverTransportMode FSM
downgrade / upgrade / overlap coordination with Profile 02 A7/A8
restart / generation fences
acceptance matrix + closeout
```

**OUT OF SCOPE:**

```text
Profile 02 wire / SRTP / fan-out mechanics (CLOSED)
Profile 01 Membership / key Facts
Meeting UI / session admission UX
E3a optimization qualification field runs (parallel evidence only)
Numeric timeouts until R2/R3/R8 adjudication
```

Publication channel wire format MAY be Profile 03 control seam or dedicated successor
(R4 decides structure; not pre-merged into Profile 02).

---

## Adjudication map (one question at a time)

```text
P03-R1   MulticastReceiveLiveness observation ownership
P03-R2   ALIVE / SUSPECT / DEAD state semantics
P03-R3   Liveness evidence + timers / hysteresis (structure; numerics in R3 only after R2)
P03-R4   Publication fact identity + freshness / stale rejection
P03-R5   Sender-side PerReceiverTransportMode FSM
P03-R6   Downgrade semantics (MULTICAST → UNICAST_FALLBACK)
P03-R7   Upgrade / recovery semantics (UNICAST_FALLBACK → MULTICAST)
P03-R8   Bounded transition overlap (TransitionOverlapMaxMs = 120 — ACCEPTED B1)
P03-R9   Failure / restart / generation fences (F1 typed — ACCEPTED)
P03-R10  Acceptance matrix + closeout (C1–C10 + X1–X3 — ACCEPTED)
```

**Process:** each Ri ends ACCEPT / REJECT / DEFER with frozen contract text. No timer
numerics in R1. R3/R8 own numeric bounds only after R2 semantics closed.

---

## P03-R1 — MulticastReceiveLiveness observation ownership

**Status: ACCEPTED** (see `0058-profile-03-r1-liveness-observation-ownership-adjudication.md`)

**Question:** Who may originate `MulticastReceiveLiveness`?

**Frozen (summary):** Only the receiving Module, from **actual local multicast media
ingress observation**. Sender-side and proxy signals are non-inputs.

---

## P03-R2 — ALIVE / SUSPECT / DEAD state semantics

**Status: ACCEPTED** (see `0058-profile-03-r2-liveness-state-semantics-adjudication.md`)

| State | Meaning | Optimization eligibility |
|-------|---------|--------------------------|
| ALIVE | Sustained recent ingress proven | ELIGIBLE |
| SUSPECT | Evidence insufficient | NOT_PROVEN |
| DEAD | Liveness lost (current scope) | NOT_ELIGIBLE |

```text
initial = SUSPECT (no UNKNOWN fourth state)
SUSPECT ≠ ALIVE for eligibility
DEAD ≠ permanent incapability
isolated packet ≠ ALIVE; isolated gap ≠ DEAD

R2-I1..I4 frozen
Timers / transitions → R3; sender policy → R5/R6
```

---

## P03-R3 — Liveness evidence + timers / hysteresis

**Status: ACCEPTED — MODEL T1**
(see `0058-profile-03-r3-liveness-timers-hysteresis-adjudication.md`)

```text
Evidence clock: admitted multicast ingress only; distinct RTP identity per unit
Earn/recovery ALIVE: span >= window AND count >= min (anti-burst)

V1 constants:
  AliveEvidenceWindowMs=1000, AliveMinValidPackets=40
  SuspectAfterNoIngressMs=300, DeadAfterNoIngressMs=1000
  RecoveryEvidenceWindowMs=1000, RecoveryMinValidPackets=40

120 < 300 < 1000; earn window >= dead timeout
DEAD → ALIVE direct recovery allowed (full recovery hysteresis)

REJECTED T2 single-packet ALIVE | T3 120ms DEAD | T4 count-only
```

---

## P03-R4 — Publication fact identity + freshness

**Status: ACCEPTED — MODEL P1**
(see `0058-profile-03-r4-publication-freshness-adjudication.md`)

```text
MulticastReceiveLivenessFact {
  receiverModuleId, conferenceMediaIncarnation, livenessState,
  publicationOrderingIdentity { publisherIncarnation, observationSequence },
  observedAtMonotonicTime  // non-authoritative for ordering
}

ordering → newer; freshness → FRESH | STALE (sender knowledge, not 4th liveness state)
stale ALIVE ≠ ELIGIBLE; stale ≠ forge receiver DEAD

Sender: REJECT_STALE_SCOPE | REJECT_STALE_ORDER | REJECT_DUPLICATE
PublicationFreshnessTimeout: semantic frozen in R4; numeric = 1000ms (G-RES-4-P3)

R4-I1..I5 frozen | P2/P3/P4 REJECTED
```

---

## P03-R5 — Sender-side PerReceiverTransportMode FSM

**Status: ACCEPTED — MODEL M1**
(see `0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`)

```text
States: MULTICAST | UNICAST_FALLBACK only
Initial: UNICAST_FALLBACK

FRESH+ALIVE     → MULTICAST eligible (upgrade needs explicit sender transition)
FRESH+SUSPECT   → UNICAST_FALLBACK required (immediate)
FRESH+DEAD      → UNICAST_FALLBACK required
STALE / no fact → UNICAST_FALLBACK required

Downgrade: fail-safe on loss of proof
Upgrade: fresh ALIVE = eligibility only, not auto MULTICAST (M3 REJECT)

M2 DEAD-only fallback | M4 multicast-first initial REJECTED
```

---

## P03-R6 — Downgrade semantics

**Status: ACCEPTED — MODEL D1**
(see `0058-profile-03-r6-downgrade-execution-adjudication.md`)

```text
Trigger: any loss of MULTICAST eligibility (R5)
Execution: immediately activate unicast leg; bounded A7 overlap MAY exist
Authoritative: sender commit when unicast leg admitted/activated → UNICAST_FALLBACK

FORBIDDEN wait: DEAD-only, Membership, owner, renegotiation, ACK, mcast teardown
FORBIDDEN order: stop multicast then start unicast (D3)

Unicast fail → explicit failure; MUST NOT retain MULTICAST as correctness

D2/D3/D4 REJECTED | R6-I1..I4 frozen | TransitionOverlapMaxMs → R8
```

---

## P03-R7 — Upgrade / recovery semantics

**Status: ACCEPTED — MODEL U1**
(see `0058-profile-03-r7-upgrade-execution-adjudication.md`)

```text
fresh ALIVE = re-entry eligibility only (R3 recovery hysteresis already applied)
Upgrade: sender explicit, optional (MAY defer); not mandatory like downgrade

Flow: eligible → upgrade pending → multicast leg active + unicast authoritative
      → sender commit MULTICAST → unicast drains in bounded overlap (R8)

Abort: eligibility lost before commit → ABORT_UPGRADE → UNICAST_FALLBACK

U2 auto-upgrade | U3 packet bypass | U4 ACK required REJECTED
R7-I1..I4 frozen
```

---

## P03-R8 — Bounded transition overlap

**Status: ACCEPTED — MODEL B1**
(see `0058-profile-03-r8-transition-overlap-adjudication.md`)

```text
TransitionOverlapMaxMs = 120 (V1 unified; aligns MaxPlayoutDelayMs)

Lifecycle observability (NOT transport modes):
  STABLE_MULTICAST | STABLE_UNICAST
  TRANSITION_TO_UNICAST | TRANSITION_TO_MULTICAST

transitionStart = sender commits to transition
overlapDeadline = transitionStart + 120ms

Downgrade: immediate unicast (R6); overlap ends → STABLE_UNICAST
Upgrade timeout/abort: → STABLE_UNICAST; never force MULTICAST

One active transition per (Source, receiver); downgrade preempts upgrade
No instant reverse during downgrade (B4 REJECT)

R8-I1..I6 frozen | B2/B3/B4 REJECTED
```

---

## P03-R9 — Failure / restart / generation fences

**Status: ACCEPTED — MODEL F1 (typed fences)**
(see `0058-profile-03-r9-restart-generation-fences-adjudication.md`)

```text
Core: fence invalidates state; does not manufacture liveness

Three typed fences (NOT one generic RESET):
  Publisher restart     → local SUSPECT + new publisherIncarnation lineage
  conferenceMediaIncarnation → hard scope fence (liveness/pub/mode/transition/ingress)
  Key-generation fence  → Source-wide atomic K1→K2; no slot straddle (A4)

Publisher restart ≠ incarnation change (R4)
Key fence ≠ liveness DEAD; does NOT reset R3 by default
Key fence ≠ new RTP Source/timeline (A2/A3)

Active transition on incarnation or key fence → CANCEL; no old-scope commit
Loss of proof → UNICAST_FALLBACK; no cached ALIVE resurrection

R9-I1..I6 frozen | F2/F3 REJECTED
```

---

## P03-R10 — Acceptance matrix + closeout

**Status: ACCEPTED — MATRIX C1–C10 + X1–X3 FROZEN**
(see `0058-profile-03-r10-acceptance-closeout-adjudication.md`)

```text
C1–C10 map R1–R9; X1–X3 cross-boundary (stale+downgrade, incarnation fence, key fence)
P03 tests mock P02; no SRTP byte re-verification
Publication transport (G-RES-4): semantic contract CLOSED (P1–P7); evidence PENDING (C1–C12)

Closeout tiers:
  R1–R10 adjudication     → COMPLETE
  C1–C10 + X1–X3 PASS     → CONTRACT COMPLETE (PENDING)
  G-RES-2 semantics       → CLOSED
  G-RES-2 evidence        → PENDING
  Media Engine            → NOT AUTHORIZED

Next: G-RES-3 evidence collection (C1–C4 COMPLETE)
```

---

## Relationship to Profile 02

```text
Profile 02  WHAT bytes / legs / timeline / cap
Profile 03  WHEN to choose multicast vs unicast per receiver
```

Profile 03 MUST invoke Profile 02 only through `PerReceiverTransportMode` + frozen
delivery directives. MUST NOT redefine wire semantics.

---

## E3a positioning (non-blocking)

```text
E3a / 3f-1  optimization-path / granularity evidence
            does NOT gate Profile 03 adjudication
            does NOT reopen Profile 02

E3a.3b lesson (establish-then-die)  inputs R2/R3/R7 anti-flap design
E3a join/IGMP/send success            explicitly NON-inputs to R1
```

---

## References

- `docs/analysis/0058-profile-03-r10-acceptance-closeout-adjudication.md`
- `docs/analysis/0058-profile-03-r9-restart-generation-fences-adjudication.md`
- `docs/analysis/0058-profile-03-r8-transition-overlap-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0058-profile-02-amendment-unicast-fanout-outline.md`
- `docs/analysis/0058-profile-02-a10-acceptance-corpus-closeout.md`
- `docs/analysis/0058-implementation-profile-03-media-runtime.md`
- `docs/analysis/adr0058-e3a-underlay-multicast-status.md`

## Next action

**P03-R10 ACCEPTED.** Profile 03 adjudication chain **COMPLETE**.
Implementation acceptance C1–C10 + X1–X3 **PENDING**.
**Next action:** implement G-RES-3 benchmark harness; collect **3×VALID PASS @ N=9** (C4 E1).
