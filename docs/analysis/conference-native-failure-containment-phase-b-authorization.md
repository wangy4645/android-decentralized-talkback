# Phase B Authorization — L4 MediaUsable + Scenario C

**ID:** conference-native-failure-containment-phase-b-authorization  
**Date:** 2026-08-22  
**Version:** v0.1  
**Type:** Implementation Boundary / Authorization Record  
**Status:** **ACCEPTED** · 2026-08-22 · **AUTHORIZED** (bounded AUTH-B-1–B-6)

**Signoff:**

| Role | Signoff | Date |
|------|---------|------|
| **Product Owner** | ACCEPTED | 2026-08-22 |
| **Media/Architecture Owner** | ACCEPTED | 2026-08-22 |

Scope frozen at signoff: AUTH-B-1–B-6 IN · L3 · native partition · topology/recovery semantics · P0.1g Acceptance 1 OUT.

**Prerequisite (all frozen):**

| Gate | Status | Artifact |
|------|--------|----------|
| Decision 1 | Option Z ACCEPTED | [decision](./conference-native-failure-containment-decision.md) |
| Failure Domain Contract | FROZEN L1–L4 taxonomy | [contract](./conference-failure-domain-contract.md) |
| Phase A / PR-A5 | **CFC-1 PARTIAL FIELD PASS (E∧D)** | [phase-a5-auth](./conference-native-failure-containment-phase-a-runtime-wiring-authorization.md) |
| Phase B Readiness | **P-B1–P-B5 CLOSED** | [readiness review](./conference-phase-b-readiness-review.md) |
| CPP/UI fuse | FIELD VERIFIED | field: `cpp-ui-fuse-field-20260822-105530` |
| Phase 3 Health UI | CLOSED (binary shim retained) | [0056-phase-3-health-ui-contract.md](./0056-phase-3-health-ui-contract.md) |
| B2-1 | RETAIN / PASS | [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md) |

```text
CFC-1 PARTIAL FIELD (E∧D)
        ↓
Phase B Readiness (P-B1–P-B5)
        ↓
Phase B Authorization  ← this gate
        ↓
L4 MediaUsable + ConferenceL4RoomState + Scenario C wiring
        ↓
CFC-1 FULL FIELD (E∧D∧C)
```

---

## Authorization statement (single sentence)

> **Authorize read-only L4 adjudication:** `MediaUsable` evaluation → `ConferenceL4RoomState` → room UI bind, plus bounded field adjudication for **Scenario C**, completing **CFC-1 FULL** — **without** L3 domain lifecycle, topology mutation, or native partition.

**No authorization for:** L3 `DOMAIN_FAILED` · domain reset/quiesce · native partition · P0.1g Acceptance 1 · topology/recovery semantic change.

---

## Goal (narrow)

Prove on field devices:

```text
L1/L2 facts (existing Phase A)
        ↓
critical fact evaluation (P-B1)
        ↓
MediaUsable evaluation (P-B3)
        ↓
ConferenceL4RoomState (P-B5)
        ↓
room pill reads l4RoomState — not CONNECTING for failure-only cases
        ↓
Scenario C auditable chain
```

**Not the goal:**

```text
Fix native hang / per-edge SRD independence
P0.1g Acceptance 1 PASS
L3 domain authority lifecycle
Production 8–10p Gate 3/4 PASS (separate run cards)
CPP/UI fuse isolated commit (hygiene track)
```

---

## Frozen product predicates (from readiness review)

All implementation MUST conform to [conference-phase-b-readiness-review.md](./conference-phase-b-readiness-review.md):

```text
P-B1  critical edge C1–C4; C3 = critical trigger only → MediaUsable → Health
P-B2  count / avatar DEGRADED ✗ direct room adjudication
P-B3  Scenario C anchor authority; spoke local chrome only
P-B4  Room「会议音频异常」· Avatar failure「不可用」
P-B5  ConferenceL4RoomState parallel; Phase 3 binary shim preserved
```

---

## Scope IN

### AUTH-B-1 — MediaUsable evaluator (anchor authority)

```text
Input (consume only):
  ConferenceTopologySnapshot.actualMediaEdges
  critical facts (P-B1 C1–C4)
  anchor hear/speak + relay/program observations (P-B3)
  recovery progress facts (diagnostic — INV-056-14)

Output:
  mediaUsable: Boolean
  evaluation trace (observability)
```

**Anchor bar (frozen):** HEAR ∧ SPEAK + critical relay/program usable; **not** all star edges CONNECTED.

### AUTH-B-2 — ConferenceL4RoomState adjudicator

```text
Input:
  mediaUsable
  session Established fact
  recoveryInFlight / recoveryFailed
  product terminal predicate (readiness review CONFERENCE_FAILED)

Output:
  ConferenceL4RoomState:
    ONLINE | DEGRADED | CONFERENCE_FAILED | NOT_ESTABLISHED
```

**Mapping (frozen):**

```text
mediaUsable=true                           → ONLINE
mediaUsable=false ∧ Established ∧ recoverable → DEGRADED
mediaUsable=false ∧ terminal predicate      → CONFERENCE_FAILED
pre-Established bootstrap                   → NOT_ESTABLISHED
```

### AUTH-B-3 — Phase 3 binary shim (non-breaking)

```text
ConferenceHealthUiProjectionContract:
  l4RoomState     ← new authority for room pill
  roomFacing      ← ONLINE iff l4RoomState==ONLINE else NOT_ONLINE
  roomOnline      ← l4RoomState==ONLINE

U1–U8 fixtures MUST remain PASS without modification of Phase 3 enum.
```

### AUTH-B-4 — Room UI bind (read-only)

```text
Meeting pill / DisplayResolver:
  MUST read l4RoomState
  DEGRADED          → ROOM_DEGRADED ·「会议音频异常」
  CONFERENCE_FAILED → ROOM_FAILED pill (new kind)
  NOT_ESTABLISHED   → CONNECTING (existing)
  ONLINE            → LIVE (existing)

MUST NOT:
  infer l4RoomState in ViewModel
  map DEGRADED → CONNECTING drawable/string
  derive room state from count(avatar DEGRADED)
```

Participant avatar fuse (Phase A) **unchanged** — failure terminal →「不可用」.

### AUTH-B-5 — Observability

```text
CONFERENCE_HEALTH conferenceId=... mediaUsable=... l4RoomState=ONLINE|DEGRADED|CONFERENCE_FAILED|NOT_ESTABLISHED userFacing=...
CONFERENCE_L4_ADJUDICATED ... criticalFacts=... mediaUsable=... l4RoomState=...
```

### AUTH-B-6 — Scenario C field run (after ACCEPTED + impl)

Bounded field on Anchor host (M01), building on E∧D replay:

```text
S3-class: E+D with room ONLINE (l4RoomState=ONLINE, pill LIVE or neutral LIVE)
S4-class: same-cause multi-impact → critical C3 → MediaUsable eval → l4RoomState=DEGRADED if predicate false
S5-class: anchor relay impaired + recoverable → l4RoomState=DEGRADED · room pill「会议音频异常」
```

Gate: **CFC-1 FULL FIELD** = Scenario E ∧ D ∧ C.

---

## Scope OUT (first-batch)

```text
L3 DOMAIN_FAILED classification / projection / lifecycle
Domain quiesce · lease forcible release · native domain reset
ConferenceNativeExecutionDomain semantic change
WebRtcSharedFactory / native partition / D-β / D-γ
Topology / ActualMediaEdgeSet / meshGeneration mutation from Health
Recovery obligation ownership change
P0.1g Acceptance 1 field rerun or adjudication
Gate 3 / Gate 4 capacity field
New failure taxonomy beyond Failure Domain Contract
Re-open Phase A L1/L2 classifier semantics
```

**If a PR requires any OUT item → STOP → separate authorization.**

---

## Hard gates

```text
G-B-1  MediaUsable is the ONLY room-level adjudication entry (P-B2)
G-B-2  C3 is critical trigger only — not automatic l4RoomState=DEGRADED
G-B-3  count / avatar DEGRADED MUST NOT set l4RoomState
G-B-4  l4RoomState MUST NOT mutate topology or recovery targets
G-B-5  Phase 3 ConferenceRoomFacing enum MUST NOT gain tri-state values
G-B-6  failure terminal MUST NOT map to CONNECTING / RECONNECTING / joiningHint
G-B-7  Field PASS wording MUST say CFC-1 FULL FIELD — not PARTIAL alone
```

---

## Implementation invariants

```text
I-B-1   DOMAIN_BLOCKED ≠ automatic l4RoomState=DEGRADED
I-B-2   Single non-critical EDGE_FAILED / DOMAIN_BLOCKED MAY leave l4RoomState=ONLINE (S3)
I-B-3   Health / L4 read-only — no AdmittedRecoveryTarget emission
I-B-4   obligationOpen alone MUST NOT override mediaUsable=true (INV-056-14)
I-B-5   Spoke-local MediaUsable affects spoke chrome only — not anchor room authority
I-B-6   Desk Gate / unit fixtures for L4 are additive — Field PASS is separate evidence
```

---

## Acceptance — CFC-1 FULL FIELD PASS

**PASS iff:**

```text
Scenario E FIELD PASS (retained from Phase A)
AND
Scenario D FIELD PASS (retained from Phase A)
AND
Scenario C FIELD PASS (new — this authorization)
```

### Scenario C must show

```text
cause → impact → MediaUsable evaluation → l4RoomState chain auditable
CONFERENCE_L4_ADJUDICATED (or equivalent) with l4RoomState explicit
Room pill matches l4RoomState (not CONNECTING for failure-only S3)
S3 replay: l4RoomState=ONLINE while M03/M04 avatar「不可用」
S4/S5-class: l4RoomState=DEGRADED with room pill「会议音频异常」when MediaUsable=false + recoverable
```

### Forbidden PASS wording

```text
P0.1g PASS / Acceptance 1 PASS
native isolation proven
L3 DOMAIN_FAILED implemented
Gate 3 / Gate 4 PASS
Phase B production complete
```

### Required PASS wording

```text
CFC-1 FULL FIELD PASS (Scenario E ∧ D ∧ C)
P0.1g Acceptance 1 remains HOLD — ORIGINAL
L3 DOMAIN_FAILED not in scope
```

---

## Field constraints

| Item | Policy |
|------|--------|
| HOST / Anchor | M01 `HTUBB21B09220661` |
| Topology | `CURRENT_ANCHOR = M01` for adjudication |
| Devices | M01–M04 as available; role swap OK for E/D (S3-class) |
| USER_LEAVE | avoid during directed Scenario C capture |
| M04 offline | adjudicate from M01 logs acceptable for room L4 |

---

## Tags (required on logs / adjudication)

```text
Phase B · CFC-1 FULL · Scenario C
P-B1–P-B5 FROZEN · L3 OUT · Acceptance 1 HOLD
B2-1 RETAIN · Native partition NOT AUTHORIZED
```

---

## Posture block

```text
Phase A / CFC-1 E+D        FIELD PASS
CPP/UI fuse                 FIELD VERIFIED
Phase B readiness           P-B1–P-B5 CLOSED
Phase B authorization       ACCEPTED (2026-08-22)
Implementation              AUTHORIZED (bounded)
Scenario C field            AUTHORIZED (post-impl)
CFC-1 FULL                  pending Scenario C field
```

---

## Sign-off

- [x] Product Owner signoff (2026-08-22)
- [x] Media/Architecture Owner signoff (2026-08-22)
- [x] Authorization **ACCEPTED**
- [x] Scenario C field run card issued
- [ ] Implementation PR scope reviewed against AUTH-B-1–B-6
- [ ] Field adjudication recorded
