# G-RES-4-P6 — Incarnation, Publisher Restart, and Fence Integration (Adjudication)

## Status

```text
G-RES-4-P6: ACCEPTED — MODEL L1
Date:       2026-09-03
Parent:     0060-gres4-publication-transport-contract-outline.md
Prerequisites: G-RES-4-P1..P5 ACCEPTED
        Profile 03 R9 F1 (typed fences — runtime semantics)
        Profile 03 R4 (ordering / scope)
Scope:  map R9 typed-fence semantics to publication transport lineage/cache
        receiver surface at sender
NOT IN SCOPE: new fence types; restart election protocol invention
              key-generation crypto details (R9 / Profile 02)
Next:       G-RES-4-P7 complete; G-RES-3 N_max + capacity envelope
```

---

## Question

P6 does **not** redefine R9 fences. It maps **R9 typed-fence semantics** to the
**publication transport lineage and sender cache** so that:

```text
No old lineage or old conference scope publication may regain freshness
or multicast eligibility after its fence.
```

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **L1** | Typed lineage replacement + scope cache invalidation | **ACCEPT** |
| **L2** | Different `publisherIncarnation` automatically treated as newer | **REJECT** |
| **L3** | Compare `observationSequence` across `publisherIncarnation` values | **REJECT** |
| **L4** | On `conferenceMediaIncarnation` change, retain fresh ALIVE cache | **REJECT** |
| **L5** | Key generation forces publication lineage reset each rotation | **REJECT** |
| **L6** | Sender restart restores persisted ALIVE as FRESH | **REJECT** |

---

## Core principle (frozen)

```text
P6 = R9 fence semantics applied to sender publication knowledge plane:

  publisherIncarnation fence  → lineage replacement + invalidate prior lineage cache
  conferenceMediaIncarnation  → hard scope fence + full cache clear
  sender restart              → empty knowledge; P4 bootstrap recovery
  key-generation fence        → publication lineage unchanged (R9 default)
```

---

## Typed fence table — publication transport obligations (frozen)

| Fence | Publication side MUST | MUST NOT |
|-------|----------------------|----------|
| **`publisherIncarnation` A→B** | B becomes current publisher lineage; invalidate A knowledge/freshness/order; accept valid B facts per P5 | Carry A ALIVE to B; forge DEAD on restart |
| **`conferenceMediaIncarnation` C1→C2** | Atomically invalidate all C1 accepted fact, freshness clock, ordering, bootstrap state; C2 starts no-fact | C1 fact refreshes C2 freshness; cross-scope seq compare |
| **Sender restart** | Local publication cache has no FRESH authority; bootstrap / push recovery | Persisted ALIVE → immediate multicast eligibility |
| **Key generation K1→K2** | Publication lineage **unchanged**; media execution obeys R9 key fence separately | Auto-discard valid liveness fact; manufacture SUSPECT/DEAD |

---

## observationSequence scope (frozen — G4-P6-I1)

```text
observationSequence ordering is meaningful ONLY within one publisherIncarnation.

MUST NOT compare seq across different publisherIncarnation values.

Example:
  A seq = 900
  B seq = 1
  → B seq=1 MAY legitimately replace A seq=900 after valid lineage replacement
```

```text
FORBIDDEN (L3):
  reject B seq=1 because 1 < 900 across incarnations
  accept A seq=901 after B established because 901 > 1 globally
```

---

## Publisher restart — lineage replacement (frozen)

### Receiver side (R9 — pointer)

```text
receiver restart:
  new publisherIncarnation
  MulticastReceiveLiveness = SUSPECT
  R3 evidence windows cleared
```

### Sender side — P6 transport mapping

```text
current publisher lineage = A
receive valid fact from B with livenessState = SUSPECT, seq = 1
  (same conferenceMediaIncarnation; passes G4-P6-I7 validity)

→ ACCEPT_NEW_LINEAGE (or equivalent R4 accept on successor incarnation)
→ invalidate ALL A knowledge:
     lastAcceptedFact from A
     freshness clock derived from A
     ordering state for A
→ current lineage = B
→ current knowledge = B/SUSPECT
→ UNICAST_FALLBACK (loss of prior ALIVE proof; not forged DEAD)
```

```text
After B established:
  late A / ALIVE / seq = 999
  → REJECT_STALE_LINEAGE
  → MUST NOT refresh freshness
  → MUST NOT resurrect ALIVE eligibility
```

### Valid replacement — security boundary (frozen — G4-P6-I7)

```text
different publisherIncarnation ≠ automatically newer (L2 REJECT)

New publisher lineage B may replace A ONLY when:
  conferenceMediaIncarnation matches sender current scope
  receiverModuleId matches fact authority (R1)
  authentication / integrity valid (P2)
  B satisfies existing R4/R9 restart/incarnation validity rules
    (implementation identity binding — NOT invented in P6)

FORBIDDEN:
  arbitrary unfamiliar B resets current knowledge without validity checks
  forged or replayed old incarnation accepted as successor
```

```text
P6 defines transport-side replacement semantics on acceptance.
P6 does NOT invent restart election or publisherIncarnation assignment protocol.
```

---

## Conference incarnation — hard scope fence (frozen — G4-P6-I4)

```text
conferenceMediaIncarnation C1 → C2

Sender MUST atomically invalidate for C1:
  accepted current fact
  lastAcceptedPublicationTime
  current publisherIncarnation tracking
  lastAcceptedSequence (per lineage)
  pending PublicationBootstrap state
  publication-derived multicast eligibility proof

Enter C2:
  no accepted fact for C2
  knowledge = unavailable / STALE
  PerReceiverTransportMode = UNICAST_FALLBACK (R5)
```

```text
Recovery:
  P3 periodic push and/or P4 PublicationBootstrap
  → first R4-accepted C2 fact

Late C1 packets (any publisherIncarnation, any seq):
  → REJECT_STALE_SCOPE
  → MUST NOT refresh C2 freshness
  → MUST NOT resurrect C1 ALIVE in C2
```

Aligns with R9-I2 / R9-I3.

---

## Sender restart (frozen — G4-P6-I5)

```text
Continues P4 safe-default; P6 adds cache-plane clarity:

sender runtime restart
  → publication knowledge empty (no FRESH authority)
  → PerReceiverTransportMode = UNICAST_FALLBACK
  → PublicationBootstrap + periodic recovery
```

```text
Even if persisted diagnostic record exists:
  C1 / publisher B / ALIVE / seq = 437

MUST NOT:
  lastAcceptedPublicationTime := now
  treat persisted state as FRESH or eligibility proof

Persisted fact = historical hint only (P4 / L6 REJECT)
```

---

## Key-generation fence — publication lineage unchanged (frozen — G4-P6-I6)

```text
MulticastReceiveLivenessFact identity dimensions (R4):
  receiverModuleId
  conferenceMediaIncarnation
  publisherIncarnation
  observationSequence

Key generation K1 → K2 is NOT a publication identity dimension in V1.
```

```text
Unless a separate contract explicitly changes multicast receive observation scope
(not asserted in R9 V1):

  K1 → K2 does NOT create new publication lineage
  does NOT invalidate accepted liveness fact
  does NOT manufacture SUSPECT or DEAD at publication layer
```

```text
Media execution obeys R9 key fence independently (Profile 02 / sender runtime).
Publication transport continues same B/seq lineage across key rotation.
```

```text
FORBIDDEN (L5):
  force publication lineage reset on every key rotation
  → spurious loss-of-proof / UNICAST_FALLBACK churn on crypto rotation
```

---

## Superseded lineage — freshness prohibition (frozen — G4-P6-I2/I3)

```text
Once valid replacement publisher lineage B supersedes A:

  all A facts are superseded for eligibility
  A facts MUST NOT refresh freshness (even if recently received)
  A facts MUST NOT update lastAcceptedFact after B established
```

Same for C1 scope after C2 fence.

---

## Combination cases (frozen acceptance vectors)

### X1 — Publisher restart

```text
A / ALIVE / seq=900 accepted
→ receiver restart
→ B / SUSPECT / seq=1 accepted (valid replacement)
→ late A / ALIVE / seq=901 arrives

PASS when:
  B/SUSPECT current; A/901 REJECT_STALE_LINEAGE
  freshness from B only; UNICAST_FALLBACK until earned ALIVE via normal path
  seq not compared 901 vs 1 across incarnations
```

### X2 — Conference incarnation fence

```text
C1 / B / ALIVE / seq=120 accepted
→ conferenceMediaIncarnation C1 → C2
→ sender cache cleared; UNICAST_FALLBACK
→ late C1 / B / ALIVE / seq=121 arrives

PASS when:
  REJECT_STALE_SCOPE
  C2 freshness not refreshed by C1 packet
  C2 knowledge requires new accepted C2 fact (push or bootstrap)
```

### X3 — Key fence

```text
C1 / B / ALIVE / seq=200 FRESH accepted
→ key generation K1 → K2 media fence

PASS when:
  publication lineage B remains valid; seq=200 knowledge may remain FRESH
     until P3 timeout or newer fact (if publications continue)
  no fabricated SUSPECT/DEAD from key change alone
  media execution follows R9 key fence; mode FSM not spuriously reset by P6 alone
```

---

## Relationship to P5 (frozen)

```text
P5: disposition within valid lineage; reject stale reorder
P6: when lineage/scope changes; what is valid; what cache to clear

P5 defers publisherIncarnation supersession legality → P6
P6 completes P5's REJECT_STALE_LINEAGE rules with R9 mapping
```

---

## Explicit non-decisions in P6

```text
NOT in P6:
  publisherIncarnation assignment algorithm (UUID vs counter)
  Membership-driven conferenceMediaIncarnation change trigger
  key-generation contract ROC/init details
  sender detection of C1→C2 fence (upper layer signal — must occur)
  P7 acceptance matrix numerics
```

---

## Invariants (frozen)

```text
G4-P6-I1  observationSequence ordering is scoped to one publisherIncarnation.

G4-P6-I2  A valid replacement publisherIncarnation invalidates all knowledge
          and ordering state from the prior publisher lineage.

G4-P6-I3  Facts from a superseded publisher lineage can never refresh freshness.

G4-P6-I4  conferenceMediaIncarnation change is a hard publication scope fence;
          all old-scope cache/freshness/order/bootstrap state is invalid.

G4-P6-I5  Sender restart does not resurrect cached FRESH knowledge.

G4-P6-I6  Key-generation change alone does not create a new publication lineage
          or manufacture liveness loss.

G4-P6-I7  A different publisherIncarnation must pass normal identity/auth/scope
          validity before it may replace the current lineage.
```

---

## Normative contract insert

```text
G-RES-4-P6 — Fence integration (Model L1):

Map R9 typed fences to publication sender cache/lineage plane.
Publisher restart: valid B replaces A; A late packets stale; seq scoped per lineage.
Conference incarnation: hard scope clear; C1 packets never affect C2.
Sender restart: empty FRESH knowledge (P4).
Key fence: publication lineage unchanged (R9).

L2/L3/L4/L5/L6 rejected. G4-P6-I1..I7 + X1/X2/X3 frozen.
```

---

## Consequences

```text
G-RES-4-P7: acceptance matrix + closeout only — no new publication semantics
G-RES-4 adjudication chain complete after P7 ACCEPT
Profile 03 R10 + G-RES-4 P7 = end-to-end transport evidence path
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p5-loss-reorder-gap-adjudication.md`
- `docs/analysis/0060-gres4-p4-snapshot-bootstrap-adjudication.md`
- `docs/analysis/0058-profile-03-r9-restart-generation-fences-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
