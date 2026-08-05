# Phase 3.2 Behavior Change -- Changelog

**Status:** Gate 3C FIELD VERIFIED; RNA Directed #3 PASS; Recovery Completion OPEN

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
| Gate 3C | NEGOTIATION_INTENT_TERMINAL_CLOSURE | FIELD VERIFIED (Directed #3 PASS) |
| Field W1/W2 | Device | NOT AUTHORIZED until prep sequence |

---

## Gate 3C (FIELD VERIFIED)

RNA-5 v2 frozen + implemented + Directed #3 PASS.

- PR-3C-A..D VERIFIED on `main`
- Directed #2 FAIL (historical dangling) ARCHIVED
- Directed #3 PASS — `logs/wifi-recovery-m03-rna0037-directed3-20260805-193602`
- `DEFERRED_DANGLING` CLOSED BY RNA-5 v2 + Directed #3 PASS

Plan: `docs/analysis/gate3c-negotiation-intent-terminal-closure-plan.md`.
Run card: `docs/analysis/rna-directed-3-run-card.md`.

Recovery Completion remains OPEN. Next: offline RNA-0037 evidence completion audit.

---

## Field prep sequence

1. Install Phase 3.2 APK
2. Clear collector
3. W1
4. `wifi-recovery-adjudicate.ps1` (RNA-0037 field verdict)
5. W2
6. Recovery Completion Authority review