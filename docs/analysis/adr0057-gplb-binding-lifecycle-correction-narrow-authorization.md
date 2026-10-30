# ADR-0057 Track C — GPLB Binding Lifecycle Correction Narrow Authorization (F7a / GPLB-LC)

## Status

**AUTHORIZED — IMPLEMENTATION CUT** (2026-09-02)

```text
Parent F7 / GPLB:
  CONTRACT DIRECTION RETAINED
  Wire Option A RETAINED
  ACCEPT CORRELATION FIELD PASS (20260902-095312)
  FULL FIELD CONFORMANCE FAIL / OPEN

Finding F7a:
  GPLB BINDING LIFECYCLE GAP
  CONFIRMED IMPLEMENTATION GAP

Classification:
  IMPLEMENTATION CORRECTION (not new architecture track)

Implementation cut:
  GPLB-LC AUTHORIZED

Required:
  GPLB-L1..L4
  LC-EG1..EG6

GIE / IGIE / GIDR:
  CLOSED — DO NOT REOPEN

Architecture redesign:
  NOT REQUIRED

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [GPLB narrow authorization (F7)](./adr0057-group-pc-lineage-binding-narrow-authorization.md)
- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)
- Field evidence: `talkback/logs/adr0057-track-c-case-a-20260902-095312/`

---

## Field evidence (why LC is authorized)

Run `20260902-095312` proved **both** simultaneously:

```text
GPLB correlation direction:     CORRECT
GPLB implementation lifecycle:  INCOMPLETE
```

### Retained (do not regress)

```text
GROUP_ACCEPT(L=GM2)
→ GPLB_ACCEPT_CORRELATE result=CURRENT
→ targetPcLineage=2
→ SRD on pcGeneration=2
→ signalingState=STABLE
```

The original F7 defect:

```text
late ACCEPT → get current PC → apply to wrong generation
```

is **eliminated** on the offerer ACCEPT path.

### Open (F7a)

```text
Correct lineage identity L exists
but lifecycle boundaries are wrong
```

| Symptom | Count | Mechanism |
|---------|-------|-----------|
| `GPLB_ICE_SEND_SKIPPED` `NO_LIVE_BINDING` | 360 | Binding recorded after `createOffer`; ICE emitted before binding |
| `GPLB_ICE_FENCED` `STALE` | 264 | `markTerminal` on SRD success; post-ACCEPT `ICE(L)` rejected |
| `ICE_CONNECTED` M03↔M02 | 0 | ICE lineage never closed |

---

## Not a new architecture problem

```text
F7 root contract:     RETAINED
Option A wire:        RETAINED
GIDR / GIE / IGIE:    RETAINED

No new ADR required.
```

---

## Two state axes (frozen)

Do **not** conflate:

```text
Signaling attempt state          Media binding state
────────────────────────         ────────────────────
OPEN                             LIVE
ACCEPTED                         FENCED
STALE                            RELEASED
FAILED
```

Key separation:

```text
ACCEPTED ≠ SUPERSEDED
GROUP_ACCEPT success ≠ binding termination
SRD success ≠ binding TERMINAL
```

`GROUP_ACCEPT` correlation success **may** complete the signaling attempt (`ACCEPTED`).  
It **must not** fence or release the live `GroupOfferBinding(L → P)`.

---

## Offerer binding order (frozen — no pending-L)

Do **not** introduce a second identity system (“pending-L”).  
Use explicit binding before any operation that can emit ICE for `L`:

```text
allocate offerLineageId L
→ acquire / identify PC lineage P
→ record GroupOfferBinding(L → P)          ← BEFORE createOffer
→ createOffer on P
→ setLocalDescription
→ ICE candidate generation stamped with L
→ GROUP_INVITE(L, SDP) wire handoff
```

**GPLB-L1:**

```text
Binding creation MUST precede any operation
that can synchronously or asynchronously emit
ICE belonging to that offer lineage.
```

### GIDR boundary (unchanged)

```text
media lineage exists
  ≠ GROUP_INVITE wire handoff happened
  ≠ peer accepted
```

Early binding creation **must not** change GIDR attempt / delivery semantics.

---

## GPLB-L1..L4 — lifecycle invariants

### GPLB-L1 — Binding precedes candidate emission

```text
For offer lineage L,
local L → PC binding MUST exist
before the associated PeerConnection can emit
ICE candidates for that negotiation.
```

### GPLB-L2 — ACCEPT does not terminate media binding

```text
Successful GROUP_ACCEPT correlation / SRD
MAY complete the signaling attempt (ACCEPTED).

It MUST NOT by itself fence or release
the corresponding live media binding.
```

Direct fix for `264 × STALE` post-accept ICE fence.

### GPLB-L3 — Same-lineage ICE remains valid after ACCEPT

```text
While binding L → P remains LIVE,
ICE(L) MAY be sent / received / queued / applied to P
even after GROUP_ACCEPT(L) has completed.
```

`MAY` — not a requirement that ICE occur only after ACCEPT.

### GPLB-L4 — Binding termination from media-lineage events only

Permitted fence / release causes:

```text
PC lineage explicitly replaced / superseded (new offer L2)
engine / PC released
session / group generation invalidated
attempt abandoned before publication
bounded negotiation failure
```

**Forbidden as sole trigger:**

```text
GROUP_ACCEPT received
SRD success → terminal binding
```

### Retry supersede (retained)

```text
INVITE L1 on PC-A  →  retry L2 on PC-B
→ L1 FENCED (superseded)
→ L2 LIVE
```

Must **not** allow post-accept ICE to imply:

```text
L1 LIVE + L2 LIVE + L3 LIVE …  (unbounded accumulation)
```

---

## Authorized scope (GPLB-LC)

1. Move offerer binding creation **before** offer / candidate emission (on `P` identified).
2. Separate signaling-attempt completion from media-binding lifetime.
3. Keep accepted lineage **LIVE** for same-`L` ICE until GPLB-L4 termination.
4. Fence prior lineage only on actual supersede / release / invalidation / bounded failure.
5. Preserve lineage-scoped pending ICE queues (`session, peer, L`).
6. Diagnostics:

```text
GPLB_BINDING_LIVE
GPLB_BINDING_SUPERSEDED
GPLB_BINDING_RELEASED
GPLB_ICE_APPLIED_TO_LIVE_BINDING
```

(existing `GPLB_*` family retained)

## Not authorized

```text
ICE restart
new retry loop / timer
wait-for-ICE before GROUP_ACCEPT
hold signaling attempt OPEN until CONNECTED
reuse stale PC
multiple LIVE current offer lineages per edge
fallback to current PC
change GIDR stale timeout
change generation / GBC semantics
change transmitReady semantics
re-open GIE / IGIE / GIDR
architecture redesign
```

---

## Fixtures (LC-EG1..EG6)

| Gate | Must prove |
|------|------------|
| **LC-EG1** | Binding exists before first candidate callback |
| **LC-EG2** | Candidate during offer creation stamped `L`; not `NO_LIVE_BINDING` |
| **LC-EG3** | `GROUP_ACCEPT(L)` → signaling `ACCEPTED`; binding `L` remains `LIVE` |
| **LC-EG4** | Post-ACCEPT `ICE(L)` applies to bound PC successfully |
| **LC-EG5** | Retry `L1→L2` fences `L1`; late `ICE(L1)` rejected; `ICE(L2)` accepted |
| **LC-EG6** | GIE / IGIE / GIDR / GPLB-EG* + release lifecycle regressions PASS |

Parent **GPLB-EG1..EG7** remain valid; LC fixtures add lifecycle gates.

---

## Field gate (Case A rerun)

Primary edge: **M03 ↔ M02**

```text
GROUP_INVITE(L)
→ GROUP_ACCEPT(L)
→ ACCEPT correlate CURRENT
→ SRD on bound PC
→ same-L ICE exchange
→ ICE_CONNECTED
```

**Tier-1 F7 / GPLB PASS** when lineage proof chain closes with native `ICE_CONNECTED`.

**Tier-2** `transmitReady=true` scored separately (Case A E2E).

If lineage + ICE apply are correct but ICE stays `CHECKING`, residual moves to transport / WebRTC — **not** GPLB contract failure.

---

## Sign-off

```text
☑ F7 / GPLB contract retained
☑ Option A retained
☑ F7a gap confirmed from field
☑ GPLB-L1..L4 frozen
☑ LC-EG1..EG6 frozen
☑ GPLB-LC implementation cut authorized
□ LC-EG1..EG6 PASS (unit / regression)
□ LC-EG1..EG6 PASS (unit / regression)
□ GPLB-LC FIELD CONFORMANCE (Case A)
```

---

## Decision record

```text
F7 / GPLB:
  CONTRACT DIRECTION RETAINED
  ACCEPT CORRELATION FIELD PASS
  FULL FIELD CONFORMANCE FAIL / OPEN

F7a / GPLB Binding Lifecycle Gap:
  CONFIRMED IMPLEMENTATION GAP

Correction:
  binding-before-candidate
  +
  signaling completion ≠ binding termination

Implementation:
  GPLB-LC NARROW CUT — AUTHORIZED
```
