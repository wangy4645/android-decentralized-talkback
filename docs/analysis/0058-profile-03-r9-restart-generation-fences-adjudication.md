# P03-R9 — Restart, Incarnation, and Key-Generation Fences (Adjudication)

## Status

```text
P03-R9: ACCEPTED — MODEL F1 (typed fences)
Date:   2026-09-03
Prerequisites: P03-R1..R8 ACCEPTED
        Profile 02 A1 (IngressTransportBinding)
        Profile 02 A4 (one protected artifact per slot)
        Profile 02 A2/A3 (single RTP timeline)
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 (restart / generation open questions)
Scope:  what each fence invalidates; stale-state resurrection prevention
        Publication transport wire/channel NOT decided (R10 / G-RES-4)
Next:   G-RES-4 publication transport contract (Profile 03 adjudication COMPLETE)
```

---

## Question

R1–R8 define a **stateful** optimization runtime. What happens when scope changes
due to publisher restart, `conferenceMediaIncarnation` change, or media
key-generation fence?

R9 freezes **typed fence semantics** so old incarnation / old key / old publisher
ALIVE, transition, and replay state **cannot cross** a fence.

---

## Core principle (frozen)

```text
Fence invalidates runtime state; it does not manufacture new liveness.
```

```text
conference / media / key scope changed (per fence rules below)
→ old optimization state invalid
→ receiver liveness returns to SUSPECT (where fence applies to liveness)
→ sender knowledge becomes unavailable / stale for old scope
→ sender correctness mode returns to UNICAST_FALLBACK (where applicable)
→ fresh evidence must be earned again
```

A fence MUST NOT:

```text
synthesize ALIVE without R3 earn/recovery evidence
carry C1 fresh ALIVE into C2
commit old-scope transition after fence
resurrect cached optimization proof across fence
```

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **F1** | Typed fences: publisher restart, media incarnation, key fence — each invalidates only its authorized state | **ACCEPT** |
| **F2** | Any restart/key change equivalent to full conference reset | **REJECT** (over-coupling) |
| **F3** | Preserve ALIVE/mode/transition across all fences where possible | **REJECT** (stale-state resurrection) |

---

## Three fence types (frozen — NOT one generic RESET)

| Fence event | MUST invalidate | MUST NOT change |
|-------------|-----------------|-----------------|
| **Publisher restart** | Old `publicationOrderingIdentity` lineage for that publisher instance; local R3 earn/recovery windows | `conferenceMediaIncarnation`; Conference/Source/key identity |
| **`conferenceMediaIncarnation` change** | R3 liveness evidence; R4 accepted publication; R5 mode; R8 active transition; A1 ingress bindings scoped to old incarnation; replay/dedup scoped to old incarnation | Membership truth (upper layer decides membership change separately) |
| **Media key-generation fence** | SRTP execution/replay/protected-artifact state under old key; all old-key legs for that Source | R3 liveness evidence (by default); Membership/Source RTP identity |

```text
publisherIncarnation change ≠ conferenceMediaIncarnation change

R4 introduced publisherIncarnation precisely so process restart is restart-safe
ordering without upgrading to conference incarnation change.
```

---

## Fence 1 — Publisher restart (receiver-local)

### Receiver behavior (frozen)

```text
publisher/runtime restart on receiving Module

→ MulticastReceiveLiveness := SUSPECT
→ clear R3 earn/recovery observation windows
→ new publisherIncarnation (implementation-assigned)
→ observationSequence restarts per R4 baseline
→ MUST earn ALIVE again via R3 evidence rules
```

```text
FORBIDDEN:
  restart → restore cached ALIVE
  restart → publish (newIncarnation, ALIVE) without fresh ingress evidence
```

Restart-safe ordering (R4) does **not** imply restart-safe liveness. Ordering
correctness and evidence correctness are separate.

### Sender behavior on publisher incarnation reset (frozen)

```text
known publisherIncarnation = A
sender currently holds FRESH + ALIVE proof from lineage A

first accepted fact with publisherIncarnation = B (same conferenceMediaIncarnation)

→ lineage A facts invalid for eligibility (R9-I3)
→ B facts accepted per R4 ordering rules
→ first B fact MUST NOT be treated as inherited ALIVE proof
     (receiver restarted at SUSPECT; B publications reflect earned state only)

→ previous ALIVE proof from A no longer usable
→ R5 loss-of-proof → UNICAST_FALLBACK required (R6 downgrade path)
```

```text
This is NOT forging receiver DEAD.
Receiver may be SUSPECT locally; sender lacks valid optimization proof.
```

---

## Fence 2 — `conferenceMediaIncarnation` change (hard scope fence)

### Invalidation set (frozen)

```text
conferenceMediaIncarnation C1 → C2

INVALIDATE for C1 scope:
  R3 liveness evidence and observation windows
  R4 lastAcceptedLivenessFact and sender freshness knowledge for C1
  R5 PerReceiverTransportMode optimization state for C1 bindings
  R8 active transport transition lifecycle for C1
  P02-A1 IngressTransportBinding / admitted ingress scope tied to C1
  replay / dedup / overlap decode state tied to C1
```

### New incarnation safe defaults (frozen)

```text
receiver (new C2 scope): MulticastReceiveLiveness = SUSPECT
sender   (new C2 scope): PerReceiverTransportMode = UNICAST_FALLBACK
                         no FRESH eligibility proof until new facts earned
```

```text
FORBIDDEN:
  carry C1 fresh ALIVE into C2
  complete C1 active transition after C2 fence
  accept C1-scoped publication as C2 proof (REJECT_STALE_SCOPE)
```

### Active transition on incarnation fence (frozen)

```text
conferenceMediaIncarnation fence
+ active TRANSITION_TO_UNICAST | TRANSITION_TO_MULTICAST for C1

→ CANCEL transition immediately
→ old-scope transition MUST NOT commit after fence
→ new C2 scope starts UNICAST_FALLBACK (R5 initial binding)
```

---

## Fence 3 — Media key-generation fence (Source-wide crypto execution fence)

### Relationship to P02-A4 (frozen)

```text
one Source
→ one SRTP sender context per key generation
→ one ProtectedDatagramArtifact per logical slot
→ all selected legs replicate byte-identical artifact
```

Key-generation fence is **Source-wide / all-leg atomic**. MUST NOT per-receiver
silently rotate keys.

### Execution rule (frozen)

```text
key generation K1 → K2 for Source S

STOP using K1 ProtectedDatagramArtifact on every leg of S
→ establish K2 sender/receiver SRTP context per existing key-generation contract
→ resume all legal legs under K2
```

```text
R9-I4: one logical RTP slot MUST NOT straddle two key generations.

FORBIDDEN:
  seq=500 multicast       → K1
           unicast M02    → K2
           unicast M03    → K1

REQUIRED:
  slot 499 → K1 artifact → all selected legs
  ----- key fence -----
  slot 500 → K2 artifact → all selected legs
```

### RTP timeline (frozen — pointer to A2/A3)

```text
R9-I5: key-generation change alone does NOT create a new Source or RTP timeline.

SSRC / seq / timestamp continuity governed by P02-A2/A3 and existing
key-generation contract. R9 does NOT redefine cryptographic derivation or ROC init.
```

### Liveness interaction (frozen — distinct from incarnation fence)

```text
key-generation fence:
  crypto/runtime execution fence
  NOT automatic R3 liveness evidence reset

MulticastReceiveLiveness describes multicast transport receive ability,
not cryptographic key health.

MUST NOT write:
  key change → liveness DEAD
  key change → synthetic ALIVE
```

```text
key fence:
  suspend/cancel transport execution under old key
  sender MUST NOT execute old-key transition overlap or old-key leg delivery
  receiver-owned liveness state preserved IF observation scope remains valid
     (same conferenceMediaIncarnation; ingress path unchanged)
```

Conservative sender posture during key fence:

```text
while K2 context not established for execution:
  MUST NOT rely on MULTICAST optimization without valid proof
  MAY remain UNICAST_FALLBACK or hold execution per key-generation contract
  MUST NOT commit old-scope R8 transition using K1 artifacts post-fence
```

### Active transition on key fence (frozen)

```text
key-generation fence
+ active transport transition

→ CANCEL old-key transition work
→ no old-key / old-scope transition may commit after fence
→ resume under K2 per Source-wide atomic rule above
```

Key fence does **not** by itself force `conferenceMediaIncarnation` change or
Membership mutation.

---

## Fence interaction summary

```text
                    │ liveness reset │ publication │ mode/transition │ SRTP/key legs
────────────────────┼────────────────┼─────────────┼─────────────────┼────────────────
Publisher restart   │ YES (local)    │ new lineage │ loss-of-proof   │ no (unless key also changes)
Incarnation C1→C2   │ YES            │ invalidate  │ reset + cancel  │ scope bindings reset
Key K1→K2           │ NO (default)   │ no reset    │ cancel old-key  │ atomic Source-wide
```

---

## Loss of proof → safe execution (frozen)

```text
R9-I6: After loss of valid optimization proof, safe execution resolves to
       UNICAST_FALLBACK; no cached ALIVE may be resurrected.

Applies to:
  publisher restart (sender view)
  incarnation fence
  stale / scope-rejected facts
  cancelled transition post-fence
```

---

## Invariants (frozen)

```text
R9-I1  Publisher restart resets local liveness evidence to SUSPECT,
       but does not create a conferenceMediaIncarnation change.

R9-I2  conferenceMediaIncarnation change is a hard liveness / publication /
       transport-transition scope fence.

R9-I3  No old-scope fact or transition may commit after its fence.

R9-I4  Key-generation fence is atomic across all legs of one Source;
       one slot cannot straddle two key generations.

R9-I5  Key-generation change alone does not mean multicast DEAD
       and does not create a new RTP Source/timeline.

R9-I6  After loss of valid optimization proof, safe execution resolves to
       UNICAST_FALLBACK; no cached ALIVE may be resurrected.
```

---

## Explicit non-decisions in R9

```text
NOT in R9:
  publication transport wire format / channel (G-RES-4; R10 boundary)
  PublicationFreshnessTimeout numeric
  Membership generation / conferenceEpoch semantics (Profile 01)
  network-change-as-fence (unless surfaced as incarnation change upstream)
  SRTP key derivation / ROC initialization details (existing key-generation contract)
  whether key fence implies brief execution pause vs frame drop (implementation)
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R9 — Typed fences (F1):

Core: fence invalidates state; does not manufacture liveness.

Publisher restart:
  receiver → SUSPECT, clear R3 windows, new publisherIncarnation;
  sender → lineage A invalid, loss-of-proof → UNICAST_FALLBACK.

conferenceMediaIncarnation change:
  hard scope fence — invalidate liveness, publication, mode, transition,
  C1 ingress/replay; new scope → SUSPECT / UNICAST_FALLBACK;
  cancel active C1 transitions.

Key-generation fence:
  Source-wide atomic K1→K2; no per-slot key straddle;
  cancel old-key transitions; does NOT reset R3 liveness by default;
  does NOT create new RTP timeline.

F2/F3 rejected. R9-I1..I6 frozen.
```

---

## Consequences

```text
R10: acceptance vectors for fence crossing, publisher restart, incarnation change,
     key fence atomicity, no ALIVE resurrection, cancelled transitions
Profile 03 amendment closeout after R10
G-RES-4: publication transport channel remains separate decision
```

---

## References

- `docs/analysis/0058-profile-03-r8-transition-overlap-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
- `docs/analysis/0058-profile-03-r6-downgrade-execution-adjudication.md`
- `docs/analysis/0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
- `docs/analysis/0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md`
- `docs/analysis/0058-profile-03-resilience-amendment-outline.md`
