# Phase B Readiness Review — MediaUsable / L4 Tri-State

**ID:** conference-phase-b-readiness-review  
**Date:** 2026-08-22  
**Type:** Architecture review · product adjudication · **no implementation**  
**Status:** **DRAFT — product predicates pending sign-off**

**Prerequisite:** Phase A / CFC-1 PARTIAL (E+D) **FIELD PASS** · CPP/UI fuse **FIELD VERIFIED** · [Failure Domain Contract](./conference-failure-domain-contract.md) L1–L4 frozen · [Decision 1 Option Z](./conference-native-failure-containment-decision.md)

---

## Scope lock

### IN (first-batch Phase B)

```text
L4 / MediaUsable product predicate
Scenario C (CFC-1 FULL last gate)
Room tri-state: ONLINE | DEGRADED | CONFERENCE_FAILED
```

### OUT / HOLD / DEFER

```text
L3 DOMAIN_FAILED              DEFER — not first-batch Phase B
Native partition              NOT AUTHORIZED
D-β / D-γ                     NOT AUTHORIZED
P0.1g Acceptance 1            HOLD — unchanged
CPP/UI fuse code commit       separate hygiene track — not Phase B gate
Topology / recovery mutation  forbidden
```

---

## Readiness answers (3 questions)

### Q1 — Does MediaUsable have a reusable factual basis?

**Yes — partial.**

| Layer | Exists today | Gap |
|-------|----------------|-----|
| Topology | `ConferenceTopologySnapshot`, `actualMediaEdges`, anchor mode | critical-edge set not product-frozen |
| Per-edge media | `MediaEdgeUsabilityObservation`, per-edge facts, L1/L2 terminals | `!usable` must not imply room severity |
| Recovery facts | `RecoveryProgressFact`, `recoveryInFlight` / `recoveryFailed` | must not override `MediaUsable=true` (ADR-0056 INV-056-14) |
| Room projection | `ConferenceHealthProjectionContract`, `ConferenceHealth` | **binary only** (`ONLINE` / `NOT_ONLINE`) |
| Participant projection | L-participant + UI fuse (Phase A) | per-avatar DEGRADED — **not** room authority |

**Conclusion:** Facts are sufficient to **adjudicate** tri-state. What is missing is the **product predicate**, not new runtime sensors.

### Q2 — Is L3 DOMAIN_FAILED needed now?

**No — defer.**

E+D field already covers the dominant 8–10p failure shape:

```text
cause edge (L1)  +  impact edge (L2 DOMAIN_BLOCKED)
```

L3 requires a separate **domain authority lifecycle** (quiesce / reset / lease orphan). No field evidence or product ask for "whole native domain dead → domain reset" at 8–10p scale.

**Conclusion:** L3 may feed MediaUsable later as an **input fact**; it is **not** a Phase B authorization prerequisite.

### Q3 — Is Scenario C worth doing now for 8–10p?

**Yes — but only after tri-state predicates are signed.**

8–10p Anchor-star product question is room-level:

```text
局部失败 → 房间还能不能继续？
```

Phase A answered per-participant visibility. Scenario C answers **room usability** — the last block of CFC-1 FULL.

**Conclusion:** Scenario C is first-batch Phase B **after** predicate sign-off, not parallel with L3.

---

## Product predicates (proposed — to sign off)

Room state is **L4 projection only**. Participant `DEGRADED` does **not** imply room `DEGRADED`.

### ONLINE

```text
MediaUsable = true

Room audio genuinely usable per product definition:
  - Anchor relay path for admitted critical edges is live, OR
  - Established session + at least one admitted media path meets hear/speak bar

obligationOpen / edgeRecovering / recoveryInFlight alone  →  MUST NOT downgrade room
Single non-critical participant EDGE_FAILED or DOMAIN_BLOCKED  →  MAY remain ONLINE
```

**User-facing:** room usable; failed participants show per-avatar DEGRADED (Phase A).

### DEGRADED

```text
MediaUsable = false
AND session Established (membership + topology admitted)
AND recoverable per product (recoveryInFlight OR bounded retry path open)
AND NOT CONFERENCE_FAILED terminal predicate

Typical triggers:
  - Anchor relay / critical admitted edge impaired but session not terminal
  - Same cause → multiple impact DOMAIN_BLOCKED (contract: may escalate L4)
  - Transient media path loss with recovery obligation open
```

**User-facing:** room impaired but not ended; distinguish from per-participant DEGRADED chrome.

**Frozen:** `DEGRADED` here is **room-level L4**, not avatar `EndpointStatus.DEGRADED`.

### CONFERENCE_FAILED

```text
MediaUsable = false
AND product terminal predicate satisfied
AND ConferenceHealth explicit terminal (no silent collapse)

Proposed terminal predicate (minimum bar):
  - recoveryFailed = true on room-critical path, OR
  - all admitted critical media edges unusable with no open recovery obligation, OR
  - anchor/topology authority declares session non-recoverable (read-only fact — no topology mutation from L4)

MUST NOT trigger from:
  - failure count alone
  - single non-critical spoke EDGE_FAILED / DOMAIN_BLOCKED
  - L2 DOMAIN_BLOCKED without MediaUsable evaluation
  - L3 DOMAIN_FAILED automatic escalation (L3 deferred)
```

**User-facing:** conference ended / must leave or rejoin — distinct from "one person degraded".

---

## 8–10p scenario map (Anchor-star, M01 anchor)

Topology: M01 = anchor/host · M02–M10 = spokes · `runtimeDomainRef` shared-factory contention possible (Option Z).

| # | Scenario | L1/L2 facts | Per-participant (Phase A) | **Room L4 (proposed)** | Notes |
|---|----------|-------------|---------------------------|------------------------|-------|
| S1 | Single non-critical spoke hang | M05 `EDGE_FAILED` | M05 DEGRADED | **ONLINE** | MediaUsable true; others admitted + usable |
| S2 | E only (cause, no contention) | M03 `EDGE_FAILED` | M03 DEGRADED | **ONLINE** | Same as S1 if anchor path intact |
| S3 | E+D (1 cause, 1 impact) — **field replay** | M03 `EDGE_FAILED`, M04 `DOMAIN_BLOCKED` | M03/M04 DEGRADED | **ONLINE** | Field verified; room still usable for M01/M02 |
| S4 | Same cause, 2+ impacts | M03 cause, M04+M05 `DOMAIN_BLOCKED` | multiple DEGRADED | **DEGRADED** | Contract: same cause → multiple impact may escalate |
| S5 | Anchor uplink edge fails, recovery open | M01↔M02 path impaired | anchor-side DEGRADED or local chrome | **DEGRADED** | Critical edge; recoverable |
| S6 | Anchor uplink fails, recovery exhausted | `recoveryFailed` on critical path | — | **CONFERENCE_FAILED** | Terminal |
| S7 | 3+ spokes failed, anchor relay OK | multiple L1/L2 | multiple DEGRADED | **ONLINE** or **DEGRADED** | **Product fork — decide threshold** (see open items) |
| S8 | All admitted critical edges unusable | widespread L1/L2 | widespread DEGRADED | **CONFERENCE_FAILED** | No usable relay |
| S9 | Recovery in flight, transient unusable | mixed | some RECONNECTING/DEGRADED | **DEGRADED** | `recoveryInFlight`; not terminal |
| S10 | Membership joined, media never established (bootstrap) | JOINING semantics | joining chrome | **NOT ONLINE** (pre-Established) | Out of Scenario C until Established |

**Field anchor:** S3 matches `cpp-ui-fuse-field-20260822-105530` — per-avatar DEGRADED, `recovering=[]`, room should adjudicate **ONLINE** under S3 predicate.

---

## Tension with Phase 3 binary room chrome

[0056-phase-3-health-ui-contract.md](./0056-phase-3-health-ui-contract.md) froze:

```text
mediaUsable == true   →  room ONLINE
mediaUsable == false  →  room NOT_ONLINE
No extra room enum
```

Phase B tri-state is an **L4 extension** for CFC-1 Scenario C, not UI guessing:

```text
ConferenceHealth adjudicator  →  L4 tri-state
UI read-only bind             →  room chrome
Participant fuse (Phase A)      →  per-avatar only
```

Phase B authorization must explicitly state whether Phase 3 U1–U8 room enum gains `DEGRADED` / `CONFERENCE_FAILED` or a parallel L4-facing projection — **without** making ViewModel the health authority.

---

## P-B1 — critical edge (**FROZEN** · Option A · 2026-08-22)

**Universe:** `criticalEdge` candidates ⊆ `snapshot.actualMediaEdges` (current `meshGeneration`).

**Default:** each admitted star edge `anchor→remote` is **NON-CRITICAL** for room-level `MediaUsable` when failure is attributable solely to that remote's L1/L2 and does not impair anchor relay / program path.

**CRITICAL triggers (C1–C4):**

| ID | Trigger |
|----|---------|
| **C1** | Anchor own hear/speak path impaired |
| **C2** | Anchor program / relay path impaired (`ConferenceAudioBus` fact) |
| **C3** | Same `runtimeDomainRef` / cause → multiple admitted edges unusable (**critical trigger only**) |
| **C4** | Topology authority: anchor unreachable · epoch invalid · `\|members\|>1` ∧ `actualMediaEdges` empty |

**C3 guard (frozen):**

```text
C3 = critical trigger
     ≠ ConferenceHealth final state

critical fact → MediaUsable evaluation → ConferenceHealth
```

C3 MUST NOT become implicit `multiple impact → automatic room DEGRADED`.

**Explicitly NON-CRITICAL:** N1 single remote `EDGE_FAILED` · N2 single remote `DOMAIN_BLOCKED` (impact-only) · N3 not admitted · N4 `obligationOpen` without `MediaUsable` impairment.

**Rejected:** critical = all `actualMediaEdges` (Option B) · count-only escalation (P-B2).

---

## P-B2 — failure count (**FROZEN** · Option A · 2026-08-22)

```text
失败人数 / impact 数量
≠ ConferenceHealth 直接裁决

唯一房间级入口：
MediaUsable → ConferenceHealth
```

**Forbidden direct escalators:**

```text
count(DEGRADED avatars)      ✗ → room DEGRADED
count(EDGE_FAILED)           ✗ → room DEGRADED
count(DOMAIN_BLOCKED)        ✗ → room DEGRADED
count ≥ N                    ✗ → room DEGRADED / CONFERENCE_FAILED
```

**C3 chain (reaffirmed):**

```text
C3 → critical fact → MediaUsable evaluation → ConferenceHealth
```

Multiple independent non-critical failures (S7) MAY remain room **ONLINE** if `MediaUsable=true`.

**Rejected:** Option B (percentage threshold) · Option C (count-only S4 bypass).

---

## P-B3 — hear/speak MediaUsable bar (**FROZEN** · Option A · 2026-08-22)

**Authority locus:**

```text
Scenario C / room L4     → Anchor device evaluation (authoritative)
Spoke local chrome       → spoke-local evaluation only (does not define room health)
```

**MediaUsable(anchor):**

```text
HEAR ∧ SPEAK
+ critical relay/program path usable (C2)
+ does NOT require all star edges CONNECTED
```

**MediaUsable(spoke) — local chrome only:**

```text
local critical star leg: HEAR ∧ SPEAK
does NOT reverse-define room ConferenceHealth
```

**Frozen:**

```text
HEAR-only  ≠ MediaUsable
SPEAK-only ≠ MediaUsable

count / avatar DEGRADED  ✗ direct room health input
```

Phase 2-5 H1 (`∃ usable admitted edge`) remains implementation floor only; P-B3 supersedes for product adjudication.

**Rejected:** Option B (per-device room truth) · Option C (HEAR-only tolerance).

---

## P-B4 — room vs avatar copy (**FROZEN** · Option A · 2026-08-22)

**Separate semantic channels — separate strings — separate pill styling.**

| Layer | Copy (frozen) |
|-------|----------------|
| **Room DEGRADED** | 「会议音频异常」 |
| **Avatar failure** | 「不可用」 |

```text
Room DEGRADED ≠ Avatar DEGRADED

failure terminal:
  ✗ Connecting
  ✗ Reconnecting
  ✗ joiningHint

real recovery obligation:
  → Reconnecting (unchanged)
```

**Forbidden:** shared string resource across room vs avatar failure semantics · failure terminals in connecting pill · room DEGRADED via joining aggregate.

**Rejected:** Option B (shared "Degraded") · Option C (dot-only avatar).

---

## Open product forks (must decide before Phase B authorization)

| ID | Question | Default recommendation |
|----|----------|------------------------|
| **P-B1** | Critical edge set | **FROZEN** — see above |
| **P-B2** | Failure count in room adjudication | **FROZEN** — see above |
| **P-B3** | Hear/speak MediaUsable bar | **FROZEN** — see above |
| **P-B4** | Room vs avatar DEGRADED copy | **FROZEN** — see above |
| **P-B5** | Phase 3 extension | **FROZEN** — see below |

---

## P-B5 — Phase 3 binary → L4 tri-state (**FROZEN** · Option A · 2026-08-22)

**Parallel L4 projection — Phase 3 `ConferenceRoomFacing` unchanged.**

```text
ConferenceL4RoomState:
  ONLINE | DEGRADED | CONFERENCE_FAILED | NOT_ESTABLISHED

Phase B authority: l4RoomState
Phase 3 legacy:    ConferenceRoomFacing = ONLINE | NOT_ONLINE (unmodified)
```

**Binary shim (backward compat):**

| `l4RoomState` | `roomFacing` | `roomOnline` |
|---------------|--------------|--------------|
| ONLINE | ONLINE | true |
| DEGRADED | NOT_ONLINE | false |
| CONFERENCE_FAILED | NOT_ONLINE | false |
| NOT_ESTABLISHED | NOT_ONLINE | false |

**UI binding:**

```text
Meeting pill / room banner  →  MUST read l4RoomState
Legacy roomOnline consumers →  unchanged (U1–U8 preserved)

DisplayResolver priority:
  DEGRADED            → ROOM_DEGRADED pill ·「会议音频异常」
  CONFERENCE_FAILED   → ROOM_FAILED pill
  NOT_ESTABLISHED     → CONNECTING (existing)
  ONLINE              → LIVE (existing)
```

**Forbidden:** mutate `ConferenceRoomFacing` to tri-state · ViewModel infers `l4RoomState` · count/avatar反推 · DEGRADED → CONNECTING fallback.

**Rejected:** Option B (break Phase 3 enum) · Option C (DisplayState-only, no core projection).

---

## Phase B authorization

**P-B1–P-B5 CLOSED** (2026-08-22). Authorization document:

[conference-native-failure-containment-phase-b-authorization.md](./conference-native-failure-containment-phase-b-authorization.md)

---

## Posture (this review)

```text
Phase A / CFC-1 E+D        DONE (field)
CPP/UI fuse                 FIELD VERIFIED (code not isolated for commit)
Phase B readiness           P-B1–P-B5 CLOSED
Phase B authorization       ACCEPTED (2026-08-22)
Implementation              AUTHORIZED (AUTH-B-1–B-6 bounded)
Scenario C field            AUTHORIZED (after impl + [run card](./conference-native-failure-containment-phase-b-field-run-card.md))
```

---

## Sign-off checklist

- [x] P-B1 critical edge set frozen (Option A · C1–C4 · C3 guard)
- [x] P-B2 count-based escalation rejected (Option A)
- [x] P-B3 hear/speak MediaUsable bar frozen (Option A · anchor authority)
- [x] P-B4 room vs avatar DEGRADED copy separated (Option A)
- [x] P-B5 Phase 3 parallel L4 projection frozen (Option A)
- [x] S1–S10 scenario table accepted (2026-08-22)
- [x] Phase B authorization **ACCEPTED** (2026-08-22)
