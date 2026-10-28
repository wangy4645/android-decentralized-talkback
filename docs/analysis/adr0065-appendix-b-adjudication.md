# ADR-0065 Appendix B — Patch-2C Field Adjudication

**Status:** **Appendix B Review PASS** · **ONE FIELD VERIFICATION AUTHORIZED** · **No run adjudicated yet**  
**Date:** 2026-09-20  
**Procedure:** [adr0065-appendix-b-field-procedure.md](./adr0065-appendix-b-field-procedure.md)  
**Parents:** [0065 Patch-2C contract](../adr/0065-patch-2c-activation-authority-contract.md)

```text
Implementation:       COMPLETE (Patch-2A / 2B / 2C)
Desk verification:    PASS
Appendix B Review:    PASS (B1–B5)
Field execution:      ONE M02↔M04 RUN AUTHORIZED
Field result:         NOT YET ADJUDICATED
```

---

## Purpose

Adjudicate **one** sealed log bundle from **one** authorized M02↔M04 field run.

This document does **not** re-open Patch-2C contract, Implementation Plan, or desk fixtures (P2C-A7, P2C-I2a, etc.).

---

## Verdict set (only three)

### PASS

**All** must hold:

1. **Target fault shape exercised** on M02↔M04 (see [procedure §3](./adr0065-appendix-b-field-procedure.md#3-target-fault-shape-valid-run-prerequisite))
2. **Production chain completed** on that edge through self-heal (no manual bypass):
   - obligation left `MEDIA_PENDING` / reached `ACTIVATION_REQUIRED`
   - GIDR permitted retry (`PermitRetry` / `INVALIDATED_RETRY_ELIGIBLE`)
   - existing GIE dispatched pairwise invite
   - ICE reached `CONNECTED` on M02↔M04
   - obligation reached `SATISFIED`
   - GROUP topology **not stuck** on target edge (`BUILDING` / `DIRECTORY_SYNC` / `meshMissingIce=M04` cleared)
3. **Artifact and setup valid** (§1 below)

→ Update status: **M02↔M04 FIELD VERIFIED (Appendix B PASS)** · still **Production Meeting UNCHANGED**

### FAIL

**All** must hold:

1. Target fault shape **was exercised** (not a setup miss)
2. Chain **stopped** or **violated contract** at a **named stage** (see evidence table)
3. Bundle sufficient to localize stage

→ **STOP** · read-only RCA · **no field re-run** under this authorization  
→ Patch-2C remains **IMPLEMENTED / UNIT PASS (desk)**; field issue stays **OPEN** with RCA reference

### INCONCLUSIVE

Any of:

- APK/commit does not include Patch-2A/2B/2C
- Canonical roster did not include M02↔M04
- Target 17:02-class shape **not exercised**
- Logs insufficient to determine shape **or** chain (not merely missing one debug line)

→ **Does not consume** the verification slot  
→ **STOP** the invalid run. Record the invalid reason first. A further attempt is allowed only if that reason still falls inside this authorization — not an operator-local unlimited retry.  
→ **“Did not recover” is not FAIL** unless the target shape and valid preconditions are already proven.

---

## Observability rule (frozen)

**Do not** require every internal enum to appear verbatim in logcat.

PASS is allowed when **existing frozen markers** prove the **causal chain**. Upgrade to INCONCLUSIVE only when evidence integrity is genuinely insufficient — not when a non-contract log alias is absent.

**Forbidden response to INCONCLUSIVE:** add field-only instrumentation in this track.

---

## Appendix B Review gate

| # | Gate | Result |
|---|------|--------|
| **B1** | Artifact identity | **PASS** |
| **B2** | Target fault shape | **PASS** |
| **B3** | Key chain evidence | **PASS** |
| **B4** | Verdict rules | **PASS** |
| **B5** | STOP rule | **PASS** |

**Review status:** **PASS** (2026-09-20)  
**Field:** one M02↔M04 17:02-class verification **AUTHORIZED**. Result not yet recorded.

---

## Key chain evidence (minimum — M02 primary)

Adjudicate on **M02** logs unless noted. Peer `M04` unless context indicates otherwise.

| Stage | Causal question | Sufficient evidence (any joint subset) | If missing |
|-------|-----------------|------------------------------------------|-----------|
| **S0** | Target edge required | Canonical roster includes M02↔M04 for the window | **INCONCLUSIVE** (roster / Layer 1) |
| **S1** | Delivery/signaling done, media edge absent | Delivery or GROUP_ACCEPT history **and** CHECKING / missing connected edge | Shape not formed → **INCONCLUSIVE** |
| **S2** | MEDIA_PENDING or equivalent | Obligation / route evidence of post-accept media wait | Shape miss → **INCONCLUSIVE** |
| **S3** | Grace/reevaluation → ACTIVATION_REQUIRED | Joint markers showing leave MEDIA_PENDING into activation-required | Stuck here with shape proven → **FAIL** |
| **S4** | GIDR suppress lifted → PermitRetry | Joint GIDR markers showing retry permitted after S3 | Stable SuppressSatisfied → **FAIL** |
| **S5** | Existing pairwise GIE executes | Activation ISSUE_INVITE + invite issued / GIE wire on M04 | PermitRetry with no dispatch → **FAIL** |
| **S6** | ICE CONNECTED → SATISFIED → READY | Connected edge + obligation satisfied + topology not stuck on M04 | Named stop → **FAIL** |

Missing one log string (for example a single `PMO_*` line) does **not** force INCONCLUSIVE when other markers jointly prove the stage.

**Note:** Stage order may overlap in time; adjudication is **causal completeness**, not strict timestamp monotonicity of log lines.

---

## Shape exercise gate (INCONCLUSIVE checklist)

Mark **INCONCLUSIVE** if any:

- [ ] No historical delivery success on M02→M04 before reactivation attempt
- [ ] ICE was already CONNECTED when accept completed (wrong shape)
- [ ] M04 not in canonical roster during window
- [ ] Operator forced manual re-invite / UI bypass on target edge
- [ ] Bundle missing M02 logcat entirely

---

## FAIL stage taxonomy (RCA routing)

| Stop stage | Likely route |
|------------|--------------|
| S3 stuck (never ACTIVATION_REQUIRED) | Patch-2B drain / observation router RCA |
| S4 stuck (SuppressSatisfied after ACTIVATION_REQUIRED) | Patch-2C C3-G2 / GIDR AND RCA |
| S5 stuck (PermitRetry but no GIE) | Layer A/B choke RCA |
| S6 media stuck (dispatch, no ICE) | GIE / media / ICE — **not** automatic Patch-2C revert |
| S6 topology stuck (ICE OK, not READY) | Topology projection — **not** Patch-2C scope expansion |

Field FAIL **does not** authorize desk contract changes without new ADR.

---

## Adjudication record (template — fill after run)

```text
Run bundle:     talkback/logs/adr0065-appendix-b-________/
Artifact SHA:   ________________
Session:        ________________
Stimulus note:  ________________

Shape exercised:        YES / NO
Chain S0..S6:           pass / fail at S_ / inconclusive
Verdict:                PASS / FAIL / INCONCLUSIVE
Consumes verification:  YES (PASS/FAIL only) / NO (INCONCLUSIVE)
STOP enforced:          YES / NO
RCA reference:          (FAIL only) ________________
```

---

## Status transitions (after authorized run)

| Verdict | Patch-2C | M02↔M04 | Field | Meeting |
|---------|------------|---------|-------|---------|
| **PASS** | IMPLEMENTED / UNIT PASS (desk) + **Appendix B PASS** | **FIELD VERIFIED** | Closed for this track | UNCHANGED |
| **FAIL** | IMPLEMENTED / UNIT PASS (desk) | OPEN + RCA | RCA only | UNCHANGED |
| **INCONCLUSIVE** | IMPLEMENTED / UNIT PASS (desk) | OPEN / NOT FIELD-VERIFIED | Slot not consumed | UNCHANGED |

---

## One-line statement (post-adjudication)

> (Fill after run) Appendix B \<PASS|FAIL|INCONCLUSIVE\>: Patch-2C production wiring \<did|did not\> complete one legal M02↔M04 self-heal on the 17:02 fault shape.
