# ADR-0050 R2a — Readiness Attribution Audit (desk)

**Status:** **AUTHORIZED** · **NO PATCH** · 2026-08-11  
**Parent finding:** [adr0050-r2a-field-finding-1.md](./adr0050-r2a-field-finding-1.md) (**PARTIAL SUCCESS**)  
**Evidence base:** `logs/adr0050-r2a-ingress-20260811-163820/`

```text
Goal: attribute READY credibility
Not: R2a code change · R2b · timeout · UVCP · EDGE_RECOVERED soak
```

---

## Questions (only)

### Q1 — READY too weak?

```text
REMOTE_NEGOTIATION_READY.ts
        vs
peer offer-received.ts / REMOTE_INGRESS_ABSENT.ts
```

| Observation | Lean |
|-------------|------|
| READY → ABSENT gap very short | H1 optimistic predicate |
| READY correct; path flap after send | H2 delivery race |

### Q2 — PENDING exit / episode binding

| Check | Ask |
|-------|-----|
| Event vs sticky | one-shot rising edge or lasting flag? |
| Episode bind | `attempt` / `obligationGen` / recovery start on READY log? |
| Stale risk | old episode READY reused for new attempt? |

---

## Required next-field delta (if re-run)

Prove equivalence of:

```text
READY_EPOCH == OFFER_EPISODE
```

via existing fields (`attempt`, `obligationGen`, `recoveryStartedAtMs`, `lastIngressAtMs`) or one additive log — **no behavior change** until H1/H2 decided.

---

## Exit criteria for this audit

| Outcome | Next |
|---------|------|
| H1 confirmed | authorize **predicate refinement IC** only |
| H2 confirmed | leave R2a; route delivery-window separately |
| Ambiguous | one more narrow field with episode-bound READY log — still no R2b |
