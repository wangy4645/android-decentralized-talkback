# ADR-0056 Phase 3 — ConferenceHealth → UI CLOSED

## Status

**PHASE 3 UI — COMPLETE / PASS** (2026-08-18)

**Scope of PASS:** Decision 5 room-facing projection + ViewModel/DisplayResolver wiring + U1–U8 + UI read-only inspection.

**Not claimed:** Gate 3/4 field · product 8p/10p PASS · ADR-0055 Track P/O.

**Parent:** [UI Scope](./0056-phase-3-health-ui-scope.md) · [U1–U8](./0056-phase-3-health-ui-contract.md) · [ADR-0056](../adr/0056-conference-topology-first.md) Decision 5

---

## What PASS means

User-visible room availability is a **read-only projection of ConferenceHealth**.

```text
ConferenceHealthBinder
        ↓
ConferenceHealthUiProjectionContract
        ↓
TalkbackSessionSnapshot.conferenceHealthUi
        ↓
ViewModel / DisplayResolver
        ↓
Room ONLINE | NOT_ONLINE
```

```text
mediaUsable == true   →  ONLINE
RECOVERING / edgeRecovering / recoveryInFlight
        ↓
MUST NOT override ONLINE
```

ViewModel does **not** infer `MEDIA_USABLE`. Runtime remains lifecycle/control-plane.

---

## Acceptance

| Check | Result |
| ----- | ------ |
| U1–U8 | PASS |
| Health ONLINE + runtime RECOVERING → LIVE | PASS |
| Health NOT_ONLINE → 非 LIVE | PASS |
| Health unavailable → legacy recovery pill | PASS |
| UI → topology / edges / recovery target / capacity | 0 |
| ViewModel → mediaUsable inference | 0 |

---

## Formal door

```text
PHASE 3 UI — COMPLETE / PASS (UI scope)

Contract        PASS
Implementation  PASS
U1–U8           PASS
Static          PASS

Gate 3 / 8p Field     NOT AUTHORIZED
Gate 4 / 10p Field    NOT AUTHORIZED
R28 / RNA / Completion FROZEN
```

Do **not** expand ADR-0056 contracts from this closeout. Next independent track: [Gate 3 8p Field Plan](./0056-gate-3-8p-field-plan.md).
