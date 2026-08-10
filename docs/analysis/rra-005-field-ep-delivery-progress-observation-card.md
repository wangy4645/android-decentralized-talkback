# RRA-005 Field EP — Recovery Protocol Observability Validation

**ID:** rra-005-field-ep  
**Date:** 2026-08-10  
**Type:** **Observability validation** · **NOT** Recovery Success Validation  
**Status:** **FIELD EP PASS (observability only)** · **NOT** Recovery functional PASS  
**Build:** Phase-2 `ReattachDeliveryProgressFacade`  
**SSID:** `happy` · Devices: M01 / M02 / M03  
**Evidence:**
- `talkback/logs/rra005-field-ep-20260810-164933/` (EP1 continuous)
- `talkback/logs/rra005-field-ep-posthoc-20260810-170452/` (EP2–EP5 ring-buffer dump)

---

## Verdict (FROZEN)

| Label | Result |
|-------|--------|
| **Field EP (observability)** | **PASS** |
| Recovery functional / media restore | **NOT PASS** · out of scope |

```text
Milestone:
  before: Recovery failed → unknown where it died
  after:  episode classifiable as Progress / Delivery / Completion
```

Do **not** merge these two verdicts.

---

## Governance stamp (FROZEN)

```text
INV-T3                 PASS / CLOSED
RRA-001~005            COMPLETE
Phase-2 Delivery       IMPLEMENTED
Field EP               PASS (observability only)

Current unresolved:
  REMOTE_RECEIPT_ACKED
          ↓
  Completion convergence
          ↓
  EDGE_RECOVERED

Next:
  RCA-001 Post-Receipt Completion Attribution
  Scope: Receipt → EDGE_RECOVERED
  Phase-1: C1–C3 · matrix first · no code
  Frozen: INV-T3 / Phase-2 / Delivery / membership / retry
  entry: docs/analysis/rca-001-post-receipt-completion-attribution-entry.md
```

---

## Gate 1 — Phase-2 coverage: PASS

```text
TRANSPORT_SENT → DELIVERY_PROGRESS_ARMED → OBTAINED | EXPIRED
SENT → ARMED coverage = 100%
```

| EP | Phase-2 | Class |
|----|---------|-------|
| EP1 16:50:26 | ARMED → OBTAINED | **B** Delivery PASS · Completion FAIL |
| EP2 16:57:38 | ARMED → OBTAINED | **B** same |
| EP3 16:59:20 | ARMED → EXPIRED | **C** Delivery no evidence · owner fulfilled |
| EP4 17:01:10 | ARMED → EXPIRED | **C** same |
| EP5 17:03:21 | no SENT (`SEND_FAILED`) | **E2** pre-delivery — do not pollute Phase-2 |

`EDGE_RECOVERED` count across run: **0** (functional restore not scored).

---

## Gate 2 — Classification completeness: PASS

Failures are no longer a black box.

### EP1 / EP2 (high-value Result 3)

```text
SENT → ARMED → RECEIPT → Completion
  → ICE_RESTART_GATE_BLOCKED (OFFER_AWAITING_ANSWER)
  → FAILED_MEDIA
```

```text
Delivery PASS · Completion FAIL
```

### EP3 / EP4

```text
SENT → ARMED → EXPIRED → episode OPEN
```

```text
Delivery unknown/no evidence
≠ Recovery failed
≠ Retry missing
```

Phase-2 owner fulfilled.

### EP5

```text
SEND_FAILED → E2 (dispatch/admission/send)
```

Do not enter Delivery classification.

---

## Directions excluded / weakened

| Claim | Verdict |
|-------|---------|
| Phase-2 did not help | **False** — EP1/EP2 have RECEIPT |
| TRANSPORT_SENT is pure fiction | **Incomplete** — SENT may yield evidence or vanish; needs Phase-2 |
| Membership sole root | **Further weakened** — Delivery OK yet EDGE_RECOVERED=0 |

---

## New main line (NOT recovery reopen)

Schedule / admission / delivery have answers. Remaining:

```text
REMOTE_RECEIPT_ACKED → ? → EDGE_RECOVERED
```

Strong signal: `ICE_RESTART_GATE_BLOCKED (OFFER_AWAITING_ANSWER)`.

Suggested next track name (design only until authorized):

```text
ICE-COMPLETION-001
or
RCA-001 Recovery Completion Attribution
```

**Not:** RMCA reopen · “retry ICE” · more Phase-2 · membership reopen.

### Attribution questions (C1–C4)

| Q | Ask |
|---|-----|
| **C1** | After receipt, who owns completion progress? |
| **C2** | Is `OFFER_AWAITING_ANSWER` = peer miss / peer ignore / answer loss / FSM forbid? |
| **C3** | Why M01 `joined=2` while `EDGE_RECOVERED=0`? (asymmetric presentation ≠ leave) |
| **C4** | Does Completion truth ≠ ICE state (like Progress ≠ Delivery)? |

---

## One-line

> Field EP PASS (observability): system can answer *where* restore died — next RCA-001 Receipt→EDGE_RECOVERED (C1–C3), not media fix.
