# Gate 3C — NEGOTIATION_INTENT_TERMINAL_CLOSURE Implementation Plan

**Status:** AUTHORIZED (2026-08-05). Design mapping only — implementation follows this plan.

**Prerequisite (accepted):** [ADR-0037 RNA-5 v2 Intent Terminal Contract](../adr/0037-rna-5-intent-terminal-contract-amendment.md) **ACCEPTED / FROZEN**

**Parent:** [Phase 3.2 design review](./phase32-recovery-negotiation-behavior-design-review.md)

**Goal:** Prove code obeys RNA-5 v2 — every exit from recovery negotiation leaves `RECOVERY_NEGOTIATION_INTENT_TERMINAL`; media lifecycle cannot close RNA transaction silently.

---

## Authorization boundary

### Allowed

- Intent terminal single-writer consolidation (`closeNegotiationIntent`)
- `NEGOTIATION_INTENT_CLOSE_REQUEST` bridge from DeferredIntent / media paths
- Unit + controller integration tests with strict terminal assertions
- Independent negotiation-intent budget clock (dual-clock option C)

### Forbidden

- Owner resolver / glare policy changes (RNA-2 / RNA-3)
- Membership convergence (RCA-0036)
- Completion predicate changes
- UI / presence projection
- Watchdog budget increase for attempt clock
- Forced deferred ICE execute

---

## Current code vs RNA-5 v2 (gap map)

| RNA-5 rule | Current behavior | Gap |
|------------|------------------|-----|
| RNA-5.1 creation gate | `recordMediaActionDeferred` emits `RECOVERY_NEGOTIATION_INTENT` DEFERRED for **all** `DeferredReason` including `MEDIA_NOT_READY` | Ghost intent (`intentId=null`) |
| RNA-5.2 single writer | Terminal emission scattered: `expireDeferredIceRestartIntent`, `onNegotiationGlareAcceptRemote`, `enterFailedMediaResidency`; `DeferredIntentAuthority` emits `RECOVERY_ICE_RESTART_INTENT_TERMINAL` only | Split writer; media terminal ≠ RNA terminal |
| RNA-5.4 SUPERSEDED | `supersedeAttempt` → `expireDeferredIceRestartIntent(SUPERSEDE:*)` works when negotiation slot active; direct `DeferredIntentAuthority.requestSupersede` / `releaseIntent` may skip RNA | R2 superseded without RNA knowing |
| RNA-5.5 isolation | `MEDIA_NOT_READY` defers media only at log layer but still calls `emitIntentFromContext(DEFERRED, reason.name)` | Negotiation polluted by media defer |
| Gate 3C settling | `isCapabilityBlockingAttemptClock` pauses attempt watchdog for `NEGOTIATION_SETTLING`; `gate3c_negotiationSettlingDefer_classifiesTensionOutcome` accepts `DEFERRED_DANGLING` | Directed #2 OPEN transaction |

**Key files today:**

- `ConferenceEdgeRecoveryController.kt` — deferral, expire, glare, supersede, observation context
- `DeferredIntentAuthority.kt` — slot supersede / release (no RNA callback)
- `RecoveryNegotiationObservation.kt` — intent + terminal log lines
- `RecoveryNegotiationAuthority.kt` — pure owner/glare rules only (no lifecycle writer yet)

---

## Target architecture (frozen bridge)

```text
DeferredIntent / MediaAction event
        |
        v
NEGOTIATION_INTENT_CLOSE_REQUEST
  (intentId, terminalHint, source, cause)
        |
        v
ConferenceEdgeRecoveryController.closeNegotiationIntent()
  [RNA terminal single writer — implements RNA-5.2]
        |
        +--> RECOVERY_NEGOTIATION_INTENT (state transition if needed)
        +--> RECOVERY_NEGOTIATION_INTENT_TERMINAL
        +--> mark negotiation transaction closed (episode-scoped ledger)
        |
        v
(slot release via existing releaseDeferredIntentSlot — downstream only)
```

**Writer rule:** Only `closeNegotiationIntent()` may emit `RECOVERY_NEGOTIATION_INTENT_TERMINAL`. `expireDeferredIceRestartIntent` becomes a caller that builds a close request, not a direct terminal emitter.

**Observation:** `RecoveryNegotiationAuthority` stays pure for election/glare. Lifecycle writer lives in controller (integration host) per Phase 3.2 pattern — satisfies RNA-016 when all mutation routes through one controller method.

---

## Implementation slices

### Slice 3C-1 — Terminal bridge (Gate 3C-1)

**Add** `closeNegotiationIntent(record, terminal, cause, source)` on `ConferenceEdgeRecoveryController`:

| Field | Values |
|-------|--------|
| `terminal` | `EXECUTED`, `BLOCKED_BY_GLARE`, `EXPIRED`, `SUPERSEDED` |
| `source` | `MEDIA_ACTION_SUPERSEDE`, `OBLIGATION_CLOSE`, `GLARE_RESOLVER`, `NEGOTIATION_BUDGET`, `DRAIN_EXECUTE`, ... |
| `cause` | existing string causes (preserve audit strings) |

**Refactor emitters** to call close bridge:

| Current caller | Terminal |
|----------------|----------|
| `onNegotiationGlareAcceptRemote` | `BLOCKED_BY_GLARE` |
| `expireDeferredIceRestartIntent` (after terminal mapping) | `EXPIRED` / `SUPERSEDED` / `BLOCKED_BY_GLARE` |
| drain execute path (`NEGOTIATION_CAN_EXECUTE`) | `EXECUTED` |
| obligation close / session cancel | `EXPIRED` |

**Add** `NEGOTIATION_INTENT_CLOSE_REQUEST` log in `RecoveryNegotiationObservation` (or controller onLog) with `intentId`, `terminalHint`, `source`.

**Wire DeferredIntentAuthority:**

```kotlin
// DeferredIntentAuthority constructor
onNegotiationCloseRequest: (intentId, terminalHint, source, cause) -> Unit
```

Invoke when `requestSupersede` / `releaseIntent(SUPERSEDE)` affects intent registered with `RequestingDomain.NEGOTIATION`.

Controller callback → `closeNegotiationIntent` if intent maps to active edge record.

**Do not** emit RNA terminal from `DeferredIntentAuthority.emitExpireAudit`.

---

### Slice 3C-2 — Ghost intent ban (Gate 3C-2)

**Change** `recordMediaActionDeferred`:

```text
if deferredReason == NEGOTIATION_SETTLING && iceRestartIntentId != null
    emit RECOVERY_NEGOTIATION_INTENT (CREATED/DEFERRED)
else
    media facts only (RECOVERY_MEDIA_ACTION_DEFERRED — already present)
```

Remove `emitIntentFromContext(DEFERRED, reason.name)` for `MEDIA_NOT_READY`, `ROUTE_NOT_READY`, `AUTHORITY_NOT_READY`.

**Invariant test** (new `NegotiationIntentCreationInvariantTest` or extend gate test):

| Scenario | Must observe | Must NOT observe |
|----------|--------------|------------------|
| `MEDIA_NOT_READY` defer only | `RECOVERY_MEDIA_ACTION_DEFERRED` | `RECOVERY_NEGOTIATION_INTENT` |
| `NEGOTIATION_SETTLING` + intentId | `RECOVERY_NEGOTIATION_INTENT` with matching `intentId` | `intentId=NONE` negotiation intent |

Optional static guard: `emitIntentFromContext` rejects when `ctx.intentId == null` unless `deferredReason == NEGOTIATION_SETTLING` (defense in depth).

---

### Slice 3C-3 — Settlement deadlock / dual clock (Gate 3C-3)

**Problem:** `NEGOTIATION_SETTLING` defer + `INV-REC-001` capability block pauses attempt watchdog → no `EXPIRED`.

**Add** `NegotiationIntentBudget` (episode-scoped):

```text
on negotiation intent CREATED (NEGOTIATION_SETTLING path only):
    schedule negotiationIntentDeadlineAtMs = now + negotiationIntentBudgetMs

on capability block:
    pause attempt watchdog (existing)

on negotiation intent deadline:
    NEGOTIATION_BUDGET_EXHAUSTED log
    closeNegotiationIntent(EXPIRED, source=NEGOTIATION_BUDGET, cause=...)
```

**Budget source:** reuse `iceRestartTimeoutMs` or dedicated constant (same value initially — do **not** increase attempt budget).

**Cancel** negotiation intent timer on: terminal close, supersede, episode bump.

**Directed #2 scenario test** (`gate3c_negotiationSettlingDefer_expiresWhenAnswerMissing`):

```text
setup: deferNegotiationIntent()  // NEGOTIATION_SETTLING
advance: past negotiationIntentBudgetMs (not attempt budget if blocked)
expect:
  NEGOTIATION_BUDGET_EXHAUSTED (or equivalent)
  RECOVERY_NEGOTIATION_INTENT_TERMINAL terminalState=EXPIRED
  pendingIceRestartIntentId == null
reject: DEFERRED_DANGLING as pass outcome
```

Replace permissive `gate3c_negotiationSettlingDefer_classifiesTensionOutcome` acceptance matrix.

---

## Test matrix (acceptance)

| ID | Scenario | Input / trigger | Required terminal | Required logs |
|----|----------|-----------------|---------------------|---------------|
| T1 | Deferred supersede | `supersedeAttempt` / `requestSupersede` on R1 | `SUPERSEDED` | `CLOSE_REQUEST`, `source=MEDIA_ACTION_SUPERSEDE` |
| T2 | Obligation close | `cancelSession` / obligation close | `EXPIRED` | terminal + slot released |
| T3 | Glare accept remote | `onNegotiationGlareAcceptRemote` | `BLOCKED_BY_GLARE` | no `FAILED_MEDIA_RECOVERY` |
| T4 | Successful restart | drain after `NEGOTIATION_CAN_EXECUTE` | `EXECUTED` | existing gate 3 drain path |
| T5 | Episode cancel | session cancel with open intent | `EXPIRED` or `SUPERSEDED` | gate 3A pattern |
| T6 | Ghost intent ban | `MEDIA_NOT_READY` defer | — | no `RECOVERY_NEGOTIATION_INTENT` |
| T7 | Settling deadlock | offer sent, answer missing, settling defer | `EXPIRED` | `NEGOTIATION_BUDGET_EXHAUSTED` |
| T8 | Dual lifecycle | negotiation WAITING + media BLOCKED | both states | no merged ghost intent |

**Existing tests to tighten:**

- `RecoveryNegotiationControllerGateTest.gate3c_*` — strict EXPIRED, no DANGLING pass
- `NegotiationDeferredDrainAuthorityTest` — assert RNA terminal on supersede chain
- `Pr52cDeferredIntentHoldTest` — supersede → SUPERSEDED terminal

**New tests (recommended):**

- `NegotiationIntentTerminalBridgeTest` — close request from DeferredIntentAuthority
- `NegotiationIntentCreationInvariantTest` — RNA-5.1 / RNA-5.5

---

## PR sequencing

| PR | Scope | Gates |
|----|-------|-------|
| PR-3C-A | `closeNegotiationIntent` + refactor existing terminal emitters + CLOSE_REQUEST log | 3C-1 partial; gates 2, 3A, 3B unchanged |
| PR-3C-B | DeferredIntentAuthority callback + supersede bridge | 3C-1 complete (T1) |
| PR-3C-C | Ghost intent ban in `recordMediaActionDeferred` | 3C-2 (T6) |
| PR-3C-D | Negotiation intent budget timer | 3C-3 (T7, T8) |

Each PR: unit/controller green before merge. No field flap until PR-3C-D merged.

---

## Field ladder — RNA Directed #3 (post Gate 3C)

```text
same RecoveryNegotiationKey
        ↓
RECOVERY_NEGOTIATION_OWNER_RESOLVED (same owner)
        ↓
single offer producer
        ↓
RECOVERY_GLARE_DECISION (if glare)
        ↓
RECOVERY_NEGOTIATION_INTENT (with intentId — not ghost)
        ↓
RECOVERY_NEGOTIATION_INTENT_TERMINAL  ← mandatory gate
        ↓
NEGOTIATION_RECOVERY_FACT
        ↓
RECOVERY_EDGE_RECOVERED
```

**Fail Directed #3 if:** intent created without terminal; `DEFERRED_INTENT_SUPERSEDED` without RNA terminal; `MEDIA_NOT_READY` with negotiation intent line.

---

## Status board

```text
ADR-0036 Membership                 VERIFIED / RE-SIGNED

ADR-0037 Design                     FINAL
RNA-5 v2 Terminal Contract          FROZEN

Phase 3.2
  Authority gates                   VERIFIED
  Controller gates                  VERIFIED

Gate 3C Implementation
  PR-3C-A Single terminal writer    DONE  (closeNegotiationIntent sole writer)
  PR-3C-B Supersede bridge          DONE  (dee9718)
  PR-3C-C Ghost intent ban          DONE  (8cf7bfe)
  PR-3C-D Intent budget timer       DONE  (1eb693d)
Gate 3C                             VERIFIED / READY FOR FIELD

RNA Directed #2                     ARCHIVED (pre-RNA-5; dangling intent FAIL)
RNA Directed #3                     READY
  run card: ./rna-directed-3-run-card.md

Recovery Completion                 OPEN
```

## References

- [RNA-5 v2 amendment](../adr/0037-rna-5-intent-terminal-contract-amendment.md)
- Directed #2: `talkback/logs/wifi-recovery-m03-rna0037-directed-20260805-183625`
- Controller gate tests: `RecoveryNegotiationControllerGateTest.kt`