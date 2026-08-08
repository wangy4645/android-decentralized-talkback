# ADR-0043 Appendix B — Passive Observation Adjudication

**Status:** **PASS** · **passive observation COMPLETE** · **Field NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Run:** `logs/adr0043-appendix-b-20260808-185802/`  
**Session:** `81d23632-d796-42b7-83de-605fbe2cc959` · CH-01  
**Checklist:** [adr0043-appendix-b-passive-observation-checklist.md](./adr0043-appendix-b-passive-observation-checklist.md)  
**Baseline:** PR **#128 MERGED** (`7820d87`) · `main`

---

## Verdict

```text
ADR-0043 Seam I v0:
  Implementation behavior      = PASS (desk + field logs)
  Appendix B passive observation = PASS
```

Evidence chain observed:

```text
Authority truth (M01 membership authority)
        ↓
P1 evidence (MEMBERSHIP_CONTEXT_EXISTENCE_QUERY/RESPONSE)
        ↓
O1 authorization (BLOCKED until PRESENT + allow)
        ↓
GROUP_RESYNC dispatch (trigger=MEMBERSHIP_CONTEXT_EVIDENCE)
```

---

## Point-by-point

| Appendix B check | Verdict | Evidence |
|------------------|---------|----------|
| No PRESENT → no GROUP_RESYNC | **PASS** | `AUTHORITY_MISMATCH` → `QUERY_SENT` → `PRESENT` → dispatch |
| UNKNOWN not promoted to PRESENT | **PASS** | No local UNKNOWN→PRESENT promotion observed |
| PRESENT ≠ GRANT | **PASS** | Dispatch via `MEMBERSHIP_CONTEXT_EVIDENCE`; O1 withhold before allow |
| Non-authority response rejected | **PASS** | `GROUP_RESYNC_DISPATCH_BLOCKED reason=ADR0043_AUTHORITY_MISMATCH` |

---

## Topology note (architecture fact)

```text
Conference host:          M02 (authorityId=M02)
Membership authority:     M01 (bootstrap primary)
GROUP_RESYNC destination: M01
```

Seam I and recovery completion are **decoupled**:

```text
ADR-0043:  membership context proof → allows GROUP_RESYNC
RNA/recovery: intent obligation → decides final completion
```

---

## Out-of-scope observation (not Appendix B FAIL)

Operator UI: M02 shows M03 **syncing** only.

```text
obligationState=SYNC_PENDING
stateReason=MEDIA_RECOVERED_BUT_INTENT_UNCOVERED
completionReason=DEFERRED_INTENT_UNCOVERED
```

Routed separately: [rna-intent-lifecycle-observation-analysis.md](./rna-intent-lifecycle-observation-analysis.md)

---

## One-line statement

> Appendix B PASS: Seam I v0 withholds and authorizes GROUP_RESYNC per frozen P1+O1 boundary; recovery completion and UI sync state are independent tracks.
