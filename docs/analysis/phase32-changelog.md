# Phase 3.2 Behavior Change -- Changelog

**Status:** Gate 3C VERIFIED / READY FOR FIELD; RNA Directed #3 READY

---

## Structural fixes

### Owner bootstrap ordering fix

Inbound reattach provenance must be established **before** canonical negotiation owner lock.

``text
recoveryViaInboundReattach
        |
write recovery provenance on EdgeRecoveryRecord
        |
bootstrap canonical owner (C)
        |
wire owner token validation / adoption (A)
``

Previously `upsertEdge` bootstrapped owner before `recoveryViaInboundReattach` was set.

**Code:** `ConferenceEdgeRecoveryController.upsertEdge(recoveryViaInboundReattach)`, `supersedeAttempt` clears `canonicalNegotiationOwnerModuleId` for new attempt episodes.

---

## Verification gates

| Gate | Scope | Status |
|------|-------|--------|
| Authority unit | `RecoveryNegotiationAuthorityTest` | VERIFIED |
| Negotiation stabilization | `NegotiationStabilizationGateTest` | VERIFIED |
| Controller lifecycle | `RecoveryNegotiationControllerGateTest` | VERIFIED |
| Gate 3C | NEGOTIATION_INTENT_TERMINAL_CLOSURE | VERIFIED (PR-3C-A..D) / READY FOR FIELD |
| Field W1/W2 | Device | NOT AUTHORIZED until prep sequence |

---

## Gate 3C (VERIFIED / READY FOR FIELD)

RNA-5 v2 frozen. Implementation complete on `main`:

- PR-3C-A single terminal writer
- PR-3C-B supersede bridge (`dee9718`)
- PR-3C-C ghost intent ban (`8cf7bfe`)
- PR-3C-D intent budget timer (`1eb693d`)

Plan: `docs/analysis/gate3c-negotiation-intent-terminal-closure-plan.md`.
Directed #3 run card: `docs/analysis/rna-directed-3-run-card.md`.

---

## Field prep sequence

1. Install Phase 3.2 APK
2. Clear collector
3. W1
4. `wifi-recovery-adjudicate.ps1` (RNA-0037 field verdict)
5. W2
6. Recovery Completion Authority review