# P03-R7 — Upgrade and Recovery Execution (Adjudication)

## Status

```text
P03-R7: ACCEPTED — MODEL U1
Date:   2026-09-03
Prerequisites: P03-R1..R6 ACCEPTED
        Profile 02 A7 (overlap)
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 Decision 3 (upgrade requires fresh ALIVE + explicit transition)
Scope:  earned re-entry to MULTICAST; asymmetric strictness vs downgrade
        TransitionOverlapMaxMs → R8
Next:   P03-R9 (restart / incarnation / generation fences)
```

---

## Question

How does the sender **re-enter** `MULTICAST` after `UNICAST_FALLBACK` — given
`fresh ALIVE` from the receiver?

**Frozen theme:** **earned re-entry** — stricter and more deliberate than downgrade.

```text
fresh ALIVE  = multicast re-entry eligibility only
MULTICAST    = requires explicit sender upgrade execution + commit
```

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **U1** | Fresh ALIVE = eligibility; explicit optional upgrade; abort if eligibility lost before commit | **ACCEPT** |
| **U2** | Fresh ALIVE auto-immediately switches to MULTICAST | **REJECT** (R5/M3) |
| **U3** | Few multicast packets without R3 recovery hysteresis → upgrade | **REJECT** |
| **U4** | Receiver ACK required before MULTICAST commit | **REJECT** (no ACK contract) |

---

## Asymmetry vs downgrade (frozen)

```text
Downgrade (R6): MUST fail-safe — loss of proof → leave MULTICAST
Upgrade (R7):   MAY optimize — eligibility ≠ mandatory immediate MULTICAST

Correctness path: UNICAST_FALLBACK (always available per R5 initial)
Optimization path: MULTICAST (earned, sender-executed)
```

Sender MAY remain on `UNICAST_FALLBACK` despite `fresh ALIVE` when:

```text
local resource / transition already in progress
policy throttle / hysteresis spacing
operator or implementation deferral
```

Correctness MUST NOT be compromised by deferring upgrade.

---

## Recovery evidence (pointer to R3)

```text
Receiver must publish fresh ALIVE only after R3 recovery hysteresis satisfied:
  RecoveryEvidenceWindowMs + RecoveryMinValidPackets

R7 does not re-cut R3 thresholds.
U3 rejected: sender MUST NOT upgrade on raw packet arrivals bypassing
published fresh ALIVE fact.
```

---

## Upgrade execution flow — U1 (frozen)

### Steady starting point

```text
PerReceiverTransportMode = UNICAST_FALLBACK (authoritative)
unicast leg active for R
```

### Eligibility

```text
Receiver publishes fresh ALIVE fact (R4 accepted, R3 earned)
→ sender marks multicast re-entry ELIGIBLE for R
→ sender MAY initiate upgrade (not MUST)
```

### Upgrade phases (sender-owned)

```text
1. ELIGIBLE
   fresh ALIVE + valid incarnation + sender policy allows consideration

2. UPGRADE_PENDING (optional explicit sub-state; contract semantic)
   sender decided to attempt upgrade
   multicast delivery leg activated / retained for R
   unicast leg continues (authoritative until commit)
   bounded A7 overlap permitted (R8 bounds duration)

3. COMMIT MULTICAST
   preconditions satisfied (below)
   → PerReceiverTransportMode := MULTICAST (authoritative)
   → receiver-specific unicast leg drains/stops within bounded overlap (R8)

4. ABORT_UPGRADE
   eligibility lost before commit (below)
   → remain / restore UNICAST_FALLBACK authoritative
   → multicast leg ceases to be correctness/optimization reliance per R5
```

### MULTICAST commit preconditions (all required)

```text
fresh ALIVE fact currently valid (FRESH knowledge + ALIVE state)
conferenceMediaIncarnation matches sender current scope
multicast delivery leg executable for R (socket/endpoint ready)
explicit sender upgrade decision recorded (sender authority — not receiver command)
```

```text
NOT required:
  receiver ACK
  receiver unicast media proof
  RTCP / application confirmation (Profile 02 Q7)
```

Authoritative switch = **sender-side execution commit** when preconditions met,
analogous to R6 unicast authoritative commit.

### Forbidden immediate unicast teardown (frozen)

```text
FORBIDDEN on first fresh ALIVE alone:
  immediately stop unicast leg before upgrade commit

Reason:
  fresh ALIVE is strong eligibility (R3 hysteresis already applied)
  but mode execution remains sender-owned
  unicast stays authoritative until MULTICAST commit (overlap window)
```

---

## Abort rules (frozen)

During `UPGRADE_PENDING` (after upgrade initiated, before MULTICAST commit):

```text
If eligibility lost:
  fresh ALIVE → SUSPECT | DEAD
  knowledge → STALE
  incarnation scope invalid / reset

→ ABORT_UPGRADE
→ UNICAST_FALLBACK remains authoritative
→ MUST NOT complete half-finished MULTICAST commit on stale eligibility
```

```text
Anti-flap:
  stale or withdrawn ALIVE MUST NOT drive partial upgrade completion
  E3a.3b brief false recovery class — abort protects against re-entering
  optimization on dying eligibility
```

After successful MULTICAST commit, loss of proof follows **R6 downgrade** (not R7 abort).

---

## Overlap on upgrade (A7 pointer)

```text
Before MULTICAST commit:
  unicast authoritative
  multicast MAY send same ProtectedDatagramArtifact (overlap)

After MULTICAST commit:
  MULTICAST authoritative
  unicast MAY drain within bounded overlap (R8)
  then receiver-specific unicast leg stops

Same RTP identity; duplicate discard; decode ≤ 1 (P02-A7, P03-R3)
```

`TransitionOverlapMaxMs` numeric → **R8**.

---

## Timeline continuity (frozen)

```text
Upgrade MUST NOT:
  reset SSRC / sequence / timestamp / sender ROC
  create new Source incarnation
  rotate media key epoch

Mode transition only; continues P02-A2/A3/A4 single timeline across unicast↔multicast.
```

---

## Invariants (frozen)

### R7-I1 — Optional optimization

```text
Upgrade is optional optimization, never correctness-required.
UNICAST_FALLBACK remains valid correctness path during and without upgrade.
```

### R7-I2 — Fresh ALIVE only

```text
Only current fresh ALIVE (FRESH + ALIVE published fact) may authorize upgrade attempt.
Packet arrivals without published fact MUST NOT trigger upgrade (U3 rejected).
```

### R7-I3 — Abort on eligibility loss

```text
Eligibility loss before MULTICAST commit aborts upgrade;
UNICAST_FALLBACK stays authoritative.
```

### R7-I4 — Timeline preservation

```text
Upgrade preserves Source / SSRC / seq / ROC / key epoch continuity (P02).
```

---

## Explicit non-decisions in R7

```text
NOT in R7:
  TransitionOverlapMaxMs (R8)
  minimum time between upgrade attempts (optional policy — implementation)
  publication cadence / PublicationFreshnessTimeout ms
  multi-receiver upgrade ordering
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R7 — Upgrade execution (U1):

fresh ALIVE = multicast re-entry eligibility only.
Sender explicitly decides upgrade; upgrade NOT mandatory.
Commit MULTICAST when: fresh ALIVE + valid incarnation + multicast leg executable
+ explicit sender decision.

Before commit: unicast authoritative; overlap allowed.
On commit: MULTICAST authoritative; unicast drains in bounded overlap (R8).
Eligibility loss before commit → ABORT_UPGRADE → UNICAST_FALLBACK.

U2/U3/U4 rejected. R7-I1..I4 frozen.
```

---

## Consequences

```text
R8: TransitionOverlapMaxMs + overlap lifecycle for downgrade and upgrade
R5/R6/R7 form complete asymmetric resilience execution contract
R10: upgrade abort vectors, optional deferral, no-auto-upgrade on first ALIVE
ADR-0060 upgrade path: fresh ALIVE + explicit transition — satisfied at contract layer
```

---

## References

- `docs/analysis/0058-profile-03-r6-downgrade-execution-adjudication.md`
- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
- `docs/analysis/0058-profile-03-r3-liveness-timers-hysteresis-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
