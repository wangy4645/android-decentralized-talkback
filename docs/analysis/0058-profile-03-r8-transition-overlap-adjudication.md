# P03-R8 — Bounded Transition Overlap Lifecycle (Adjudication)

## Status

```text
P03-R8: ACCEPTED — MODEL B1
Date:   2026-09-03
Prerequisites: P03-R1..R7 ACCEPTED
        Profile 02 A7 (overlap duplicate semantics)
        Profile 03 MaxPlayoutDelayMs = 120
Parent: 0058-profile-03-resilience-amendment-outline.md
Scope:  TransitionOverlapMaxMs V1 numeric + transition lifecycle boundaries
Next:   P03-R10 (acceptance matrix + Profile 03 closeout)
```

---

## Question

What is the **maximum overlap window** for mode transitions, and what is the
**sender transition lifecycle** observability model?

`PerReceiverTransportMode` remains two states only (R5). R8 adds **transition
lifecycle** labels for execution observability — not a third/fourth transport mode.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **B1** | Unified 120ms hard bound + fail-safe timeout + single active transition | **ACCEPT** |
| **B2** | No fixed upper bound; "as soon as possible" only | **REJECT** |
| **B3** | Timeout forces commit to target mode regardless of eligibility | **REJECT** |
| **B4** | Downgrade/upgrade may instantly reverse each other | **REJECT** |

---

## V1 constant (frozen)

```text
TransitionOverlapMaxMs = 120
```

```text
Single unified bound for:
  TRANSITION_TO_UNICAST overlap (downgrade)
  TRANSITION_TO_MULTICAST overlap (upgrade)

NOT separate downgrade vs upgrade timers in V1.
```

### Rationale (contractual, not re-opened)

```text
Profile 03 MaxPlayoutDelayMs = 120 (playout horizon)
Transition overlap MUST NOT outlive one full playout horizon
  → avoids A7 "bounded overlap" becoming long-term dual delivery

120ms ≈ 6 × 20ms media slots at nominal cadence
  sufficient for a few slots + jitter/decoder continuity
  aligns overlap duplicate lifetime with existing runtime envelope
```

Future tuning MAY amend constant via Profile 03 parameter amendment without
reopening R6/R7 authority semantics.

---

## Lifecycle observability states (frozen)

```text
NOT PerReceiverTransportMode states — sender runtime observability only.

STABLE_MULTICAST
STABLE_UNICAST
TRANSITION_TO_UNICAST
TRANSITION_TO_MULTICAST
```

```text
PerReceiverTransportMode (authoritative, R5/R6/R7):
  MULTICAST | UNICAST_FALLBACK only

Lifecycle state describes transition progress; authoritative mode commits
per R6/R7 independently but MUST align with lifecycle termination rules below.
```

---

## Transition start time (frozen)

```text
transitionStart =
  instant sender commits to executing the transition
  (records transition lifecycle entry for this (Source, receiver))

NOT transitionStart:
  receiver first packet arrival time
  ALIVE fact observedAtMonotonicTime
  first socket sendto on new leg
  PerReceiverTransportMode commit time alone (may be earlier/later — start is
    commitment to transition execution, typically at R6/R7 phase entry)
```

```text
overlapDeadline = transitionStart + TransitionOverlapMaxMs
```

---

## Downgrade lifecycle (TRANSITION_TO_UNICAST)

Aligns with R6 D1 immediate activation:

```text
T0: downgrade trigger (loss of multicast eligibility proof)

→ enter TRANSITION_TO_UNICAST
   transitionStart := T0 (sender commits to transition)

→ immediately activate unicast leg (R6 — NOT delayed by 120ms)

→ unicast admitted + activated
→ commit PerReceiverTransportMode = UNICAST_FALLBACK (authoritative)

→ multicast duplicate delivery MAY continue (A7 overlap)
→ overlap MUST terminate by overlapDeadline (<= 120ms from transitionStart)

→ STABLE_UNICAST
```

```text
120ms is maximum overlap lifetime, NOT permission to wait 120ms before
activating unicast. R6 immediate activation remains in force.
```

### Downgrade at overlap deadline

```text
If UNICAST_FALLBACK already committed at deadline:
  stop relying on multicast for R
  end overlap (cease non-authoritative multicast to R)
  → STABLE_UNICAST

MUST NOT:
  revert to MULTICAST as correctness path
  extend overlap without new transition
```

---

## Upgrade lifecycle (TRANSITION_TO_MULTICAST)

Aligns with R7 U1 earned re-entry:

```text
fresh ALIVE + sender explicit upgrade decision

→ enter TRANSITION_TO_MULTICAST
   transitionStart := now

→ ensure multicast leg executable
→ bounded dual delivery (unicast authoritative until commit)

→ commit PerReceiverTransportMode = MULTICAST (R7 preconditions)

→ retire receiver-specific unicast leg within overlapDeadline

→ STABLE_MULTICAST
```

### Upgrade abort before commit (R7)

```text
TRANSITION_TO_MULTICAST
+ SUSPECT | DEAD | STALE | invalid scope before MULTICAST commit

→ ABORT upgrade
→ terminate multicast transition work
→ UNICAST_FALLBACK remains authoritative
→ STABLE_UNICAST
```

### Upgrade at overlap deadline

```text
If MULTICAST NOT safely committed at deadline:
  ABORT upgrade
  → STABLE_UNICAST
  → UNICAST_FALLBACK authoritative

MUST NOT:
  force MULTICAST commit on timeout (B3 rejected)
  leave dangling multicast reliance without commit
```

---

## Fail-safe timeout asymmetry (frozen)

```text
downgrade timeout → correctness stays / goes unicast
upgrade timeout   → optimization attempt fails; stay unicast

Timeout NEVER forces successful MULTICAST upgrade.
Timeout NEVER returns to MULTICAST after failed upgrade.
```

---

## Re-entrancy and priority (frozen)

```text
At most one active transport transition per (Source, remoteReceiverModuleId)
for a given conferenceMediaIncarnation.

R8-I3: single active transition
```

### Downgrade preempts upgrade

```text
If TRANSITION_TO_MULTICAST active and downgrade trigger occurs:

  immediately ABORT upgrade
  downgrade / fallback wins (R8-I4)
  enter or continue TRANSITION_TO_UNICAST per R6
```

### No immediate reverse during downgrade

```text
If TRANSITION_TO_UNICAST active and fresh ALIVE arrives:

  MUST NOT immediately reverse into TRANSITION_TO_MULTICAST
  MUST finish/commit fallback transition per R6
  later R7 MAY start new upgrade from STABLE_UNICAST

Prevents MULTICAST→UNICAST→MULTICAST→UNICAST flutter within overlap window (B4).
```

---

## Overlap wire semantics (pointer)

```text
During overlap: same ProtectedDatagramArtifact / RTP identity (P02)
duplicate delivery → decode ≤ 1 (A7, R3)
FanoutDatagramRate / leg counting per P02-A6/A9 unchanged
```

---

## Invariants (frozen)

```text
R8-I1  TransitionOverlapMaxMs = 120 (V1).

R8-I2  Overlap deadline bounds duplicate delivery lifetime;
       does NOT delay downgrade unicast activation (R6).

R8-I3  At most one active transport transition per (Source, receiver, incarnation).

R8-I4  Downgrade has priority over upgrade.

R8-I5  Upgrade timeout/abort always resolves to UNICAST_FALLBACK / STABLE_UNICAST.

R8-I6  Transition does not reset Source / RTP / SRTP / key epoch (P02, R6, R7).
```

---

## Explicit non-decisions in R8

```text
NOT in R8:
  PublicationFreshnessTimeout ms (R4 follow-up / transport cadence)
  conferenceMediaIncarnation reset triggers (R9)
  publisherIncarnation reset (R9)
  multi-Source transition coupling
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R8 — Transition overlap (B1):

TransitionOverlapMaxMs = 120 (V1, unified downgrade/upgrade).

Lifecycle observability: STABLE_MULTICAST | STABLE_UNICAST |
  TRANSITION_TO_UNICAST | TRANSITION_TO_MULTICAST
(≠ PerReceiverTransportMode states).

transitionStart = sender commits to executing transition.
overlapDeadline = transitionStart + 120ms.

Downgrade: immediate unicast activation; overlap ends fail-safe → STABLE_UNICAST.
Upgrade: abort or timeout without commit → STABLE_UNICAST; never force MULTICAST.

Single active transition; downgrade preempts upgrade; no instant reverse during downgrade.

B2/B3/B4 rejected. R8-I1..I6 frozen.
```

---

## Consequences

```text
R9: what clears lifecycle / publication / liveness on incarnation or restart
R10: overlap deadline vectors, preemption, abort-at-120ms, no forced upgrade
A7 numeric bound now closed at Profile 03 layer
```

---

## References

- `docs/analysis/0058-profile-03-r7-upgrade-execution-adjudication.md`
- `docs/analysis/0058-profile-03-r6-downgrade-execution-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/analysis/0058-implementation-profile-03-media-runtime.md` (MaxPlayoutDelayMs)
