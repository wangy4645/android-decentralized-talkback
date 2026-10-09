# Phase B Field Run Card — CFC-1 FULL FIELD (E + D + C)

**ID:** conference-native-failure-containment-phase-b-field-run-card  
**Date:** 2026-08-22  
**Authorization:** [Phase B](./conference-native-failure-containment-phase-b-authorization.md) **ACCEPTED**  
**Readiness:** [P-B1–P-B5](./conference-phase-b-readiness-review.md) **FROZEN**  
**Gate:** CFC-1 FULL FIELD · **NOT** P0.1g Acceptance 1 · **NOT** L3

**Prerequisite:** Phase B L4 implementation merged (AUTH-B-1–B-6). Do not adjudicate Scenario C on pre-Phase-B builds.

---

## Tags (required on every log / adjudication)

```text
Phase B · CFC-1 FULL FIELD · E+D+C
P-B1–P-B5 FROZEN · L3 OUT · Acceptance 1 HOLD
B2-1 RETAIN · Native partition NOT AUTHORIZED
```

---

## Topology pre-gate

```text
HOST = M01
CURRENT_ANCHOR = M01
```

If `anchor != M01` → **INVALID RUN** (do not adjudicate CFC-1 FULL).

**Devices:** M01 `HTUBB21B09220661` · M02 `DSJ-2407041` · M03 `MDX0220416001963` · M04 as available.

Role swap for E/D (e.g. M03 cause, M04 impact) is **valid** — do not invalidate for non-standard injection role.

---

## Retained gates (must still PASS)

### Scenario E — cause edge

Same bar as [Phase A5 run card](./conference-native-failure-containment-phase-a5-field-run-card.md#scenario-e--cause-edge-hang).

```text
CONFERENCE_FAILURE_L1_CLASSIFIED ... terminal=EDGE_FAILED
CONFERENCE_FAILURE_PROJECTED ... displayState=VISIBLE_EDGE_FAILED
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E
```

### Scenario D — domain contention impact

Same bar as [Phase A5 run card](./conference-native-failure-containment-phase-a5-field-run-card.md#scenario-d--domain-contention-impact).

```text
CONFERENCE_FAILURE_L2_CLASSIFIED ... terminal=DOMAIN_BLOCKED
CONFERENCE_FAILURE_PROJECTED ... displayState=VISIBLE_DOMAIN_BLOCKED blockedByDomain=true
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D
```

---

## Scenario C — room L4 projection (new)

**Goal:** `critical facts → MediaUsable → ConferenceL4RoomState → room pill` auditable on field.

### C-1 — S3-class: E+D with room ONLINE (primary)

**Setup:** Reproduce E+D (any valid cause/impact pair). Example from field: M03 `EDGE_FAILED`, M04 `DOMAIN_BLOCKED`.

**Must see in logs:**

```text
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D
CONFERENCE_L4_ADJUDICATED ... mediaUsable=true l4RoomState=ONLINE
CONFERENCE_HEALTH ... l4RoomState=ONLINE userFacing=ONLINE
```

**Must see in UI (M01):**

```text
Failed avatars: 「不可用」 (not Connecting / Reconnecting)
Room pill: LIVE (or neutral LIVE) — NOT「会议音频异常」
recovering=[] · connectingHint=null
```

**Must NOT:**

```text
l4RoomState=DEGRADED for S3-class
Room pill CONNECTING / RECONNECTING for failure-only case
count(avatar DEGRADED) → room downgrade
```

### C-2 — S4-class: same-cause multi-impact → room DEGRADED (if MediaUsable=false)

**Setup:** Single cause edge blocks **multiple** impact edges (shared `runtimeDomainRef`).

**Must see:**

```text
C3 critical trigger in adjudication trace (multiple impact edges)
CONFERENCE_L4_ADJUDICATED ... mediaUsable=false l4RoomState=DEGRADED
CONFERENCE_HEALTH ... l4RoomState=DEGRADED
```

**Must see in UI (M01):**

```text
Room pill:「会议音频异常」
NOT CONNECTING fallback
```

**Guard:** C3 is **critical trigger only** — log must show `MediaUsable` evaluation between C3 fact and `l4RoomState=DEGRADED`.

### C-3 — S5-class: anchor relay impaired + recoverable → room DEGRADED

**Setup:** Anchor relay/program path impaired (C2) with recovery open — exact inject per lab capability.

**Must see:**

```text
CONFERENCE_L4_ADJUDICATED ... mediaUsable=false l4RoomState=DEGRADED recoveryInFlight=true (or equivalent)
Room pill:「会议音频异常」
```

**Must NOT:** `l4RoomState=CONFERENCE_FAILED` unless terminal predicate satisfied.

### C-4 — CONFERENCE_FAILED (optional capture)

Only if field naturally produces terminal predicate (recovery exhausted on critical path). Not required for minimal FULL PASS if C-1 + C-2 PASS.

---

## Adjudication

| Result | Condition |
|--------|-----------|
| **CFC-1 FULL FIELD PASS** | E PASS ∧ D PASS ∧ **C-1 PASS** ∧ (**C-2 PASS** OR documented S4 unavailable) |
| **PARTIAL** | E∧D PASS but Scenario C incomplete |
| **FAIL** | S3-class shows `l4RoomState=DEGRADED` · room CONNECTING for failure-only · missing `CONFERENCE_L4_ADJUDICATED` · count-driven room downgrade |
| **INVALID** | wrong anchor · pre-Phase-B build · USER_LEAVE during capture · recovery mutates topology |

**PASS wording (only):**

```text
CFC-1 FULL FIELD PASS (Scenario E ∧ D ∧ C)
P0.1g Acceptance 1 remains HOLD — ORIGINAL
L3 DOMAIN_FAILED not in scope
```

---

## Out of scope this run

```text
P0.1g Acceptance 1
L3 DOMAIN_FAILED / domain reset lifecycle
native partition / D-β / D-γ
topology / recovery semantic change
Gate 3 / Gate 4 capacity soak
```

---

## Record

| Field | Value |
|-------|-------|
| Build / commit | |
| Run id / log dir | |
| Scenario E | PASS / FAIL / INVALID |
| Scenario D | PASS / FAIL / INVALID |
| Scenario C-1 (S3-class) | PASS / FAIL / INVALID |
| Scenario C-2 (S4-class) | PASS / FAIL / N/A |
| Scenario C-3 (S5-class) | PASS / FAIL / N/A |
| Field verdict | |
| Notes | |
