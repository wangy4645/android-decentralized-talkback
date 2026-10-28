# G-RES-4-P2 — Transport Channel vs Correctness Dependency (Adjudication)

## Status

```text
G-RES-4-P2: ACCEPTED — MODEL C1
Date:       2026-09-03
Parent:     0060-gres4-publication-transport-contract-outline.md
            ADR-0060 (no GROUP semantic coupling; no relay topology)
Prerequisites: G-RES-4-P1 ACCEPTED (Model A)
        Profile 03 R4/R5 (fact semantics; freshness fail-safe)
Scope:  publication channel semantic independence; required transport properties;
        correctness dependency boundaries
NOT IN SCOPE: cadence / PublicationFreshnessTimeout (P3)
              snapshot / late join (P4)
              loss/reorder/duplicate wire handling (P5)
              fence transport integration (P6)
Next:       G-RES-4-P4 (snapshot / late join / sender restart recovery)
```

---

## Question

P2 is **not** “reuse old signaling socket vs build new socket.”

P2 adjudicates:

```text
1. Does publication correctness depend on any existing GROUP semantic domain?
2. What transport properties must the publication channel possess?
3. May correctness depend on delivery of any single publication message?
```

Wire substrate MAY be shared; GROUP/ADR-0057 **semantic and lifecycle** MUST NOT.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **C1** | Independent logical Conference publication channel; MAY reuse generic authenticated unicast control substrate; MUST NOT reuse GROUP/ADR-0057 semantics/lifecycle | **ACCEPT** |
| **C2** | Embed facts in existing GROUP/ADR-0057 signaling semantic/lifecycle | **REJECT** |
| **C3** | Mandate dedicated reliable TCP/stream channel as V1 requirement | **REJECT V1** (premature implementation lock-in) |
| **C4** | Multicast/broadcast publication of liveness facts | **REJECT** (circular dependency) |

---

## Frozen decision — Model C1

### Architecture (frozen)

```text
Conference Liveness Publication
        │
        │ dedicated message contract / namespace
        ▼
authenticated unicast control substrate
        │
        ├─ receiver M03 → sender M01
        ├─ receiver M03 → sender M02
        └─ receiver M03 → sender M04
```

```text
transport machinery reuse        MAY
GROUP semantic channel reuse     MUST NOT
```

```text
MAY reuse (implementation):
  generic peer-to-peer control send capability
  existing authenticated envelope / peer routing
  shared socket or connection pool infrastructure

MUST NOT reuse (semantic):
  GROUP_JOIN / GROUP_INVITE / bootstrap message semantics
  ADR-0057 generation / bootstrap lifecycle as publication authority
  Membership/GROUP session state as liveness transport prerequisite
```

### Protected boundaries (frozen)

```text
GROUP PTT / ADR-0057:
  MUST NOT be re-coupled to Conference media resilience correctness

Conference liveness publication:
  MUST NOT stop because GROUP bootstrap/session lifecycle paused or failed
  (correctness path independent of GROUP semantic channel availability)
```

---

## Required transport properties (frozen)

### MUST

```text
receiver → sender direct unicast delivery path (P1 topology)

independent of multicast media reachability
  (publication MUST NOT require multicast to announce multicast failure)

authenticated / integrity-protected
  (peer identity bound to admitted sender Module)

addressable to admitted sender identity
  (routing keyed to current admitted media sender set)

allow repeated current-state publication
  (refreshable state; not one-shot command)

failure eventually observable as freshness loss at sender
  (R4 STALE → R5 loss-of-proof → UNICAST_FALLBACK)
```

### MUST NOT

```text
require multicast or broadcast to publish MulticastReceiveLivenessFact

require owner/relay (P1-B rejected)

depend on GROUP bootstrap lifecycle or ADR-0057 semantic channel for correctness

require exactly-once delivery of any single publication message

require per-message ACK for correctness
  (ACK MAY exist for observability; MUST NOT be correctness gate)
```

### MAY

```text
reuse existing generic unicast control transport infrastructure

use reliable transport (TCP, QUIC stream, etc.) if already available
  — NOT mandated in V1; loss-tolerant refresh model is sufficient
```

---

## Refreshable state — no single-message correctness (frozen)

```text
MulticastReceiveLivenessFact is refreshable state, not a one-shot irrevocable command.

Correctness MUST NOT depend on delivery of any single publication message.
```

```text
publication lost / delayed
  → sender eventually sees STALE (R4)
  → multicast eligibility proof absent (R5)
  → UNICAST_FALLBACK (fail-safe)
```

```text
Publication transport MAY tolerate:
  loss
  duplicate
  reorder

P5 adjudicates receiver-side disposition at consumption seam.
P3 adjudicates cadence + PublicationFreshnessTimeout so STALE is bounded.
```

This avoids:

```text
reliable-stream HOL blocking
connection recovery becoming a new correctness dependency
implementation locked to TCP before cadence/TTL are known
```

---

## Circular dependency — multicast publication (REJECT C4)

```text
FORBIDDEN:
  MulticastReceiveLivenessFact → multicast/broadcast publication channel
```

```text
Failure mode:
  M02 multicast ingress broken
  → M02 must report multicast receive capability lost
  → notification itself depends on multicast
  → self-defeating architecture
```

Publication channel MUST be unicast and independent of multicast media path (C1 MUST).

---

## Transport success ≠ fact acceptance (frozen)

```text
transport delivery success (e.g. unicast send() accepted by stack)
  ≠ MulticastReceiveLivenessFact accepted as current sender knowledge
```

Sender MUST still apply R4 consumption rules:

```text
scope           (conferenceMediaIncarnation)
ordering        (publisherIncarnation, observationSequence)
duplicate       (REJECT_DUPLICATE)
freshness       (FRESH vs STALE)
```

```text
P2-I6: Publication transport layer MUST NOT bypass R4 acceptance seam.
```

---

## Rejected models — rationale

### C2 — GROUP/ADR-0057 semantic embedding (REJECT)

```text
Embedding MulticastReceiveLivenessFact in GROUP_JOIN / bootstrap / ADR-0057 lifecycle:

  couples Conference resilience to GROUP session semantics
  risks publication stopping when GROUP channel unavailable
  conflates Membership/bootstrap facts with transport optimization observation (R4)
  reopens closed ADR-0057 boundary without amendment
```

### C3 — Mandated reliable TCP/stream (REJECT V1)

```text
V1 does not require:
  dedicated new TCP connection per publication
  reliable ordered stream as correctness prerequisite

Reliable transport MAY be used where already present; correctness is proven by
refresh + freshness fail-safe, not by per-message delivery guarantee.
```

### C4 — Multicast/broadcast publication (REJECT)

See circular dependency section above.

---

## Relationship to P1 (frozen)

```text
P1: logical fan-out — each receiver → all admitted senders
P2: each leg over authenticated unicast; semantic channel independent of GROUP

P2 does not change recipient set or receiver-scoped granularity.
```

---

## Explicit non-decisions in P2

```text
NOT in P2:
  publication cadence / heartbeat interval          → P3
  PublicationFreshnessTimeout numeric               → P3
  late join / sender restart snapshot               → P4
  loss / reorder / duplicate / gap wire semantics   → P5
  incarnation / publisher restart fence transport   → P6
  message schema field layout / protobuf type name  → implementation
  specific auth mechanism (TLS, SRTP control, etc.) → existing control substrate
```

---

## Invariants (frozen)

```text
G4-P2-I1  Publication uses dedicated Conference liveness semantic channel;
          not GROUP/ADR-0057 semantic lifecycle.

G4-P2-I2  Delivery is receiver→sender authenticated unicast per P1 topology.

G4-P2-I3  Publication correctness independent of multicast media reachability.

G4-P2-I4  Correctness MUST NOT depend on any single message delivery;
          refresh + freshness fail-safe (R4/R5).

G4-P2-I5  Multicast/broadcast publication of liveness facts FORBIDDEN.

G4-P2-I6  Transport send success ≠ R4 fact acceptance.

G4-P2-I7  Generic unicast control substrate MAY be reused; GROUP semantics MUST NOT.
```

---

## Normative contract insert

```text
G-RES-4-P2 — Transport channel (Model C1):

Independent logical Conference Liveness Publication channel over authenticated
unicast control substrate (receiver → each admitted sender).

MAY reuse generic control transport machinery.
MUST NOT embed in GROUP/ADR-0057 semantic or lifecycle.
MUST NOT use multicast/broadcast publication.

Refreshable state: no exactly-once / per-message ACK correctness requirement.
Loss → eventual STALE → UNICAST_FALLBACK.

C2/C3/C4 rejected. G4-P2-I1..I7 frozen.
```

---

## Consequences

```text
P3: cadence + PublicationFreshnessTimeout numerics unconstrained by reliable-stream
    assumptions; STALE bound is the correctness timer
P4: snapshot/recovery over same unicast channel; not GROUP bootstrap
P5: duplicate/reorder tolerance explicit at consumption seam
P7: negative test — GROUP channel down, publication+correctness still viable
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p1-publication-topology-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/adr/0057-profile-authentication-establishment-provisioning-contract.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
