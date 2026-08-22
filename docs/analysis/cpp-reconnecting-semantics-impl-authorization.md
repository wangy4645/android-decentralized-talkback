# CPP-RECONNECTING-SEMANTICS — Implementation Authorization

**ID:** cpp-reconnecting-semantics-impl-authorization  
**Date:** 2026-08-22  
**Version:** v0.1  
**Type:** Implementation Boundary / Authorization Record  
**Status:** **ACCEPTED (desk)** · implementation **AUTHORIZED** · field replay **NOT adjudicated**

**Does not inherit CFC-1 Phase A authority.** Separate gate between [CPP Reconnecting Semantics Contract](./cpp-reconnecting-semantics-contract.md) freeze and implementation PRs.

**Prerequisite (all frozen):**

| Gate | Status | Artifact |
|------|--------|----------|
| CFC-1 Phase A | **DONE** | [Phase A authorization](./conference-native-failure-containment-phase-a-authorization.md) · field E∧D PASS `talkback/logs/cfc1-partial-field-20260822-101900/` |
| CPP Reconnecting Semantics | **Q1–Q3 FROZEN** | [cpp-reconnecting-semantics-contract.md](./cpp-reconnecting-semantics-contract.md) v0.1 |
| Failure Domain Contract | **FROZEN** | [conference-failure-domain-contract.md](./conference-failure-domain-contract.md) |
| ADR-0022 R27′-B | **ACTIVE** | `recoveringPeers` from `EdgeRecoveryFacts` only |

```text
CFC-1 Phase A (DONE)
    ↓
CPP Reconnecting Semantics Contract (Q1–Q3)
    ↓
CPP-RECONNECTING-SEMANTICS-IMPL (this document)   ← gate
    ↓
Implementation PR
```

---

## Authorization statement (single sentence)

> **Authorize implementation of CPP presentation fuse only** — decouple `recoveringPeers` from edge-usability `reconnecting`, fuse failure L-participant projection into UI as `DEGRADED`, and exclude failure terminals from `joiningHint` / false recovery pill membership.

**No authorization for:** CFC-1 classifier changes · B2-1 lease · recovery controller semantics · topology · MediaUsable / ConferenceHealth · native runtime.

---

## Hard gate (non-negotiable)

> **MUST NOT reconstruct any `reconnecting` / `recovering` semantics from `!usable` or `PerEdgeMediaUsabilityFact` usability alone.**

```text
!usable
    → mediaRelation / joining semantics ONLY

recovery obligation
    → EdgeRecoveryFacts ONLY

failure terminal
    → failureTerminalsByModuleId / visibleParticipants ONLY
```

Any PR that reintroduces `usable=false → reconnecting → recoveringPeers` is **REJECTED** regardless of test green.

---

## Goal (narrow)

Close the presentation integration debt identified in field RCA: CFC-1 telemetry PASS but UI false-`RECONNECTING` because CPP `recoveringPeers` and avatar bind ignored classified failure terminals.

**Not the goal:**

```text
Change CFC-1 E/D classification
Close P0.1g Acceptance 1
Fix native hang / lease semantics
Add failure aggregate pill copy
Add new EndpointStatus enum values
Change ConferenceHealth / MediaUsable
```

---

## Scope IN — five authorized items only

### AUTH-CPP-1 — `recoveringPeers` → `EdgeRecoveryFacts` only

```text
recoveringPeers
= conferenceEdgeRecoveryController
    .factsForSession(sessionId)
    .recoveringRemoteModuleIds
∩ canonicalRoster
```

**Required changes (indicative):**

- `TalkbackCoordinator.projectConferencePresenceFromTopologySnapshot`: remove `+ observed.reconnectingModuleIds` from `recoveringModuleIds` passed to `ConferencePresenceProjector.compose`.
- MESH path (`projectConferencePresenceState`): confirm `recoveringModuleIds` is `edgeFacts.recoveringRemoteModuleIds` only (no secondary source).

**Forbidden:**

```text
reconnectingModuleIds → recoveringPeers
ICE / HELLO / !usable → recoveringPeers
```

### AUTH-CPP-2 — Remove `!usable` → `reconnecting` error path

Decouple `ConferencePerEdgeMediaFactContract.viewOf` `reconnecting` flag from any downstream `recoveringPeers` or user `RECONNECTING` derivation.

**Allowed:** `!usable` continues to set `mediaRelation=NONE`, `evidence=UNKNOWN`, and **joining** axis (`INITIAL_JOIN` / `JOINING`).

**Forbidden:**

```text
viewOf(!usable).reconnecting=true
    → reconnectingModuleIds
    → recoveringPeers
    → avatar RECONNECTING
```

Implementation may retain `reconnecting` on `CppStarEdgeView` for internal axis mapping **only if** it never feeds `recoveringPeers` or obligation semantics. Prefer removing the misleading path entirely where safe.

### AUTH-CPP-3 — UI fuse: failure terminal → `DEGRADED`

**Fusion point:** ViewModel / UI bind layer — **not** `ConferencePresenceProjection`.

```text
CPP presence projection
        +
session.visibleParticipants (failure displayState)
        ↓
UI fuse
        ↓
endpoint list / avatar status
```

**Precedence:**

```text
classified failure terminal on module
    → VISIBLE_EDGE_FAILED | VISIBLE_DOMAIN_BLOCKED wins
    → EndpointStatus.DEGRADED
    → skip JOINING / RECONNECTING for that module

no failure terminal
    → existing CPP + EdgeRecoveryFacts path
```

**Authorized touchpoints (indicative):**

```text
TalkViewModel.kt                    — fuse visibleParticipants into endpoint bind
ConferenceEndpointStatusMapper.kt   — VISIBLE_EDGE_FAILED / VISIBLE_DOMAIN_BLOCKED → DEGRADED
MeetingPresenceDisplay.kt           — fuse path if needed for avatar rows
```

**Forbidden:**

```text
VISIBLE_EDGE_FAILED      → RECONNECTING
VISIBLE_DOMAIN_BLOCKED   → RECONNECTING
VISIBLE_*_FAILED/BLOCKED → CONNECTING
failure terminal inside ConferencePresenceProjector
new EndpointStatus enum values
```

### AUTH-CPP-4 — `joiningHint` excludes failure terminal

Fused hint at UI layer (may wrap or replace raw `ConferencePresenceUiBind.joiningHint`):

```text
joining candidate
= JOINED
∧ ¬mediaConnected
∧ moduleId ∉ recoveringPeers
∧ ¬classifiedFailureTerminal(moduleId)
```

**Forbidden:**

```text
"M04 joining..." when DOMAIN_BLOCKED
"M03 joining..." when EDGE_FAILED
```

### AUTH-CPP-5 — Meeting pill: real recovery obligation only

```text
Meeting pill recovering=[…]
    = recoveringPeers (post AUTH-CPP-1: EdgeRecoveryFacts only)

connectingParticipantHint
    = fused joiningHint (post AUTH-CPP-4)
```

**Forbidden:**

```text
failure terminal in recovering=[…] without controllerEdgeRecovering=true
failure-specific aggregate pill copy (this gate)
```

Diagnostic log line may retain `recovering=[…]` — must match obligation set after fix.

---

## Scope OUT (implementation drift prohibited)

```text
CFC-1 classifier / telemetry / chain validation
Phase A authorization / PR-A1–A5 contracts
B2-1 lease / NATIVE_DOMAIN_LEASE_BUSY semantics
ConferenceEdgeRecoveryController phase transitions
Recovery offer / obligation open-close semantics
Topology / ActualMediaEdgeSet / anchor failover
MediaUsable predicate
ConferenceHealth / L4 projection
Conference DEGRADED on DOMAIN_BLOCKED
native runtime / WebRtcSharedFactory / SRD paths
P0.1g Acceptance 1
new EndpointStatus values
failure aggregate pill strings
```

---

## Field replay adjudication (post-impl)

**Topology:** M01 anchor · same session class as `talkback/logs/cfc1-partial-field-20260822-101900/`

**Precondition:** CFC-1 E∧D telemetry still present (`CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E|D`).

| Check | M03 (`EDGE_FAILED`) | M04 (`DOMAIN_BLOCKED`) |
|-------|---------------------|------------------------|
| `displayState` / avatar | `DEGRADED` | `DEGRADED` |
| `inRecoveringPeers` | `false` (no obligation) | `false` (no obligation) |
| `controllerEdgeRecovering` | `false` | `false` |
| `joiningHint` mentions module | **no** | **no** |
| `EndpointStatus` | not `CONNECTING` / `RECONNECTING` | not `CONNECTING` / `RECONNECTING` |

**Meeting pill (no real obligation on any peer):**

```text
recovering=[]
connectingHint=null
```

**PASS wording (only):**

```text
CPP-RECONNECTING-SEMANTICS FIELD PASS
CFC-1 E+D telemetry unchanged
```

**FAIL triggers:**

```text
failure terminal → recoveringPeers
failure terminal → RECONNECTING or CONNECTING avatar
joiningHint contains M03 or M04
!usable reintroduced as recovering source
CFC-1 telemetry regression
```

---

## Unit test minimum (desk gate)

| ID | Assert |
|----|--------|
| T1 | `compose(recoveringModuleIds=edgeOnly)` — `reconnectingModuleIds` not unioned into `recoveringPeers` |
| T2 | `viewOf(!usable)` does not cause module ∈ `recoveringPeers` when controller not recovering |
| T3 | `ConferenceEndpointStatusMapper`: `VISIBLE_DOMAIN_BLOCKED` → `DEGRADED` |
| T4 | `ConferenceEndpointStatusMapper`: `VISIBLE_EDGE_FAILED` → `DEGRADED` |
| T5 | Fused `joiningHint`: failure terminal module excluded |
| T6 | Field replay fixture: M03+M04 classified → pill `recovering=[]`, hint `null` |
| T7 | ADR-0034 regression: `recoveringPeers` aggregate alone does not override per-peer `DEGRADED` when failure fused |

---

## PR labeling

```text
PR label: CPP-RECONNECTING-SEMANTICS-IMPL
NOT: CFC-1 · Phase A · B2-1 · Recovery fix · P0.1g
```

---

## Posture after desk acceptance

```text
CFC-1 Phase A                 DONE
CPP contract Q1–Q3            FROZEN
CPP-RECONNECTING-SEMANTICS    IMPL AUTHORIZED (desk)
Field replay                  NOT adjudicated
Hard gate                     !usable ≠ reconnecting/recovering
```

---

## Tags (logs / adjudication)

```text
CPP-RECONNECTING-SEMANTICS-IMPL · presentation fuse only
CFC-1 UNCHANGED · B2-1 OUT · Recovery semantics OUT
HARD GATE: !usable ≠ reconnecting/recovering
```
