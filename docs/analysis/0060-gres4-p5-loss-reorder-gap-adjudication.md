# G-RES-4-P5 — Loss, Reorder, Duplicate, and Gap Semantics (Adjudication)

## Status

```text
G-RES-4-P5: ACCEPTED — MODEL G1
Date:       2026-09-03
Parent:     0060-gres4-publication-transport-contract-outline.md
Prerequisites: G-RES-4-P1..P4 ACCEPTED
        Profile 03 R4 (ordering / freshness semantics)
        Profile 03 R5 (consumption; no rollback)
Scope:  sender-side disposition on lossy/reordering publication transport;
        gap is not error; no event-log replay
NOT IN SCOPE: publisherIncarnation fence legality (P6)
              wire auth mechanism details
Next:       G-RES-4-P7 (acceptance matrix + closeout)
```

---

## Question

On a **loss-tolerant, reordering, duplicating** publication transport (P2),
how does the sender apply incoming `MulticastReceiveLivenessFact` messages?

P5 is deliberately minimal:

```text
Publication is current-state replication, NOT event-log replication.
Gap means "missed refreshes" — not "missing history that must be repaired."
```

---

## Core principle (frozen)

```text
Accept only strictly newer current-state facts within the current valid scope/lineage.
Sequence gap does NOT require repair, retransmission, or ordered replay.
```

```text
Each fact means: receiver livenessState at publication time
NOT: an ordered transition event that must all be applied
```

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **G1** | Newest current-state wins; no gap repair / replay | **ACCEPT** |
| **G2** | Sequence gap must be filled before accepting newer fact | **REJECT** |
| **G3** | Gap automatically implies SUSPECT/DEAD at sender | **REJECT** |
| **G4** | Late older fact may refresh freshness | **REJECT** |
| **G5** | Duplicate may refresh freshness | **REJECT** |

---

## Frozen decision — Model G1

### Sender consumption algorithm (normative)

For incoming fact `F` for `(receiverModuleId, conferenceMediaIncarnation)`:

```text
1. if authentication / integrity invalid
     → REJECT (no state change; no freshness refresh)

2. if F.conferenceMediaIncarnation != sender current scope
     → REJECT_STALE_SCOPE

3. if F.publicationOrderingIdentity publisher lineage invalid for current binding
     → REJECT_STALE_LINEAGE
     (legality of lineage transition — P6; P5 applies once current lineage set)

4. let last = lastAcceptedFact[receiver, incarnation]
   let (pubInc, seq) = F.publicationOrderingIdentity

5. if same publisherIncarnation as last:
     if seq <= last.observationSequence
       → REJECT_STALE_ORDER or REJECT_DUPLICATE (no rollback; no refresh)
     if seq > last.observationSequence
       → ACCEPT CURRENT FACT

6. if new publisherIncarnation (successor per P6 rules):
     → ACCEPT per R4/P6 lineage rules (seq restarts; not comparable to old seq)

7. on ACCEPT:
     replace lastAcceptedFact
     refresh freshness clock (P3 G4-P3-I5)
     apply R5 mode eligibility from new livenessState + FRESH
```

```text
P5 adds transport-layer clarity to R4 ordering; does not replace R4 rejection names.
```

---

## Gap semantics (frozen — G4-P5-I2/I3)

```text
lastAcceptedSequence = 100
next received        = 104

→ ACCEPT 104 as current fact
→ DO NOT request 101..103
→ DO NOT wait for gap repair
→ DO NOT buffer pending acceptance until gap filled
```

```text
Facts 101..103 were refreshes of state-at-that-time.
Missing them does not leave sender with incomplete event history.
Current knowledge = state carried by newest accepted fact (104).
```

```text
FORBIDDEN (G2):
  withhold acceptance of 104 until 101..103 arrive
  NACK / gap-repair protocol as correctness requirement
```

Gap is **not an error condition** at sender (G4-P5-I1).

---

## Reorder semantics (frozen — G4-P5-I4)

```text
receive seq=104 → ACCEPT (e.g. SUSPECT)
later seq=102   → REJECT_STALE_ORDER (e.g. ALIVE)
```

```text
Core anti-rollback case:
  104 = SUSPECT accepted
  102 = ALIVE arrives late
  → MUST NOT resurrect ALIVE
  → current knowledge remains SUSPECT-derived
  → freshness clock unchanged (G4-P5-I5)
```

Older facts **never roll back** newer accepted state.

---

## Duplicate semantics (frozen)

```text
seq=104 received twice:
  first  → ACCEPT (or already accepted — idempotent if identical)
  second → REJECT_DUPLICATE
```

```text
Duplicate / stale / rejected facts:
  MUST NOT refresh freshness clock (P3 G4-P3-I5, G4-P5-I5)
  MUST NOT change lastAcceptedFact
  MUST NOT change PerReceiverTransportMode
```

Prevents attacker, network duplication, or stale queue data from indefinitely
extending old ALIVE proof.

---

## Gap does NOT manufacture liveness (frozen — G4-P5-I6)

```text
seq 20 → seq 24 (gap 21..23)

DOES NOT mean:
  receiver multicast ingress lost
  publisher entered SUSPECT/DEAD
  sender should synthesize SUSPECT/DEAD
```

```text
Publication gap consequences ONLY:

  (a) later newer accepted fact arrives
        → current knowledge updates

  (b) no acceptable newer fact within P3 window
        → STALE → UNICAST_FALLBACK (R5)
```

```text
FORBIDDEN (G3):
  sequence gap detected → synthesize SUSPECT or DEAD at sender
```

Violates receiver-owned liveness authority (R1/R2).

---

## publisherIncarnation — P5 vs P6 boundary (frozen)

```text
P5 MUST NOT assume:
  different publisherIncarnation → automatically newer

Late packets from old publisher incarnation A may arrive after successor B accepted.
```

```text
P6 decides:
  when publisherIncarnation B legitimately supersedes A (restart/fence)
  sender cache invalidation on lineage change

P5 freezes once current lineage is established:
  arrivals from superseded lineage A → REJECT_STALE_LINEAGE / REJECT_STALE_ORDER
  MUST NOT override current fact from lineage B
```

```text
Do not write in P5 alone:
  "new publisherIncarnation is always accepted"
```

---

## Combination cases (frozen acceptance vectors)

### X1 — Gap with state transition

```text
ALIVE seq=100 accepted
seq=101..103 lost
SUSPECT seq=104 arrives

PASS when:
  accept SUSPECT immediately
  downgrade path engaged (R6)
  no request for 101..103
  freshness clock refreshed on accept of 104
```

### X2 — Late older fact after newer accepted

```text
SUSPECT seq=104 accepted
late ALIVE seq=102 arrives

PASS when:
  REJECT_STALE_ORDER
  remain SUSPECT-derived knowledge and UNICAST_FALLBACK posture
  freshness clock unchanged
  MUST NOT resurrect ALIVE eligibility
```

---

## Relationship to P2/P3/P4 (frozen)

```text
P2: loss/reorder/duplicate tolerated at transport
P3: periodic refresh provides eventual newer facts; freshness timeout bounds outage
P4: bootstrap delivers newer fact; same G1 acceptance rules
P5: sender disposition unifies all ingress paths
```

---

## Explicit non-decisions in P5

```text
NOT in P5:
  NACK / gap-repair wire messages
  sender-side publication buffer size
  out-of-order queue retention policy beyond reject-stale
  P6 incarnation / publisher restart lineage legality rules
  cryptographic auth failure class names
```

---

## Invariants (frozen)

```text
G4-P5-I1  Publication is current-state replication, not event-log replication.

G4-P5-I2  Within a valid lineage, strictly newer fact may replace current fact
          even when sequence numbers contain gaps.

G4-P5-I3  No gap repair, retransmission history, or ordered replay is required.

G4-P5-I4  Older or duplicate facts never roll back state.

G4-P5-I5  Rejected / stale / duplicate facts never refresh freshness.

G4-P5-I6  A publication gap does not manufacture receiver SUSPECT or DEAD;
          continued absence is handled only by P3 freshness → STALE.
```

---

## Normative contract insert

```text
G-RES-4-P5 — Loss/reorder/duplicate/gap (Model G1):

Newest current-state wins within valid scope/lineage.
seq gap: accept newer; no repair of missing seq.
Reorder/duplicate: reject stale; no rollback; no freshness refresh on reject.
Gap ≠ synthesized liveness; absence → P3 STALE only.

G2/G3/G4/G5 rejected. G4-P5-I1..I6 frozen. X1/X2 frozen vectors.
```

---

## Consequences

```text
P6: lineage supersession rules; prevent old incarnation packets post-fence
P7: acceptance tests for gap, reorder anti-rollback, duplicate no-refresh, X1/X2
Profile 03 R10 C5 transport integration may reference G1 disposition
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p4-snapshot-bootstrap-adjudication.md`
- `docs/analysis/0060-gres4-p3-cadence-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
- `docs/analysis/0058-profile-03-r9-restart-generation-fences-adjudication.md`
