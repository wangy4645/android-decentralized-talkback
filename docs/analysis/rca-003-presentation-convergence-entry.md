# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN — Round-1 **ADJUDICATED (Case C)** · Round-2 **authorized** · **no code**  
**Date:** 2026-08-10  
**Name (frozen):** Presentation Convergence after Recovery  
**Not:** “UI bug” / “WiFi recovery broken” / “修 UI”  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**Round-1 adjudication:** [rca-003-round1-adjudication-20260810-210401.md](./rca-003-round1-adjudication-20260810-210401.md)  
**Round-2 card:** [rca-003-presentation-convergence-observation-round2.md](./rca-003-presentation-convergence-observation-round2.md)

## Entry gate

```text
EDGE_RECOVERED 是否已经产生？
```

Round-1: **no** → Case C · recovery **not** implicated · presentation convergence **not yet** proven.

## Round-1 one-liner

```text
recovering cleared + degraded remains
≠ recovering sticky after EDGE_RECOVERED
≠ reopen recovery
```

`recovering` and `degraded` are **different projection sources**.  
`recovering cleared ≠ fully healthy`.

## Goal (updated)

Still prove terminal → presentation propagation when F1=yes.  
Until then, Round-2 answers only: **degraded lifecycle = transient (D) or durable (E)?**

```text
Was:  “恢复后 UI 不对？”
Now:  “recovering 清除后，degraded projection 的生命周期是什么？”
```

## Out of scope

```text
INV-T3 · RRA-005 · RCA-001 · RCA-002 · acceptance · completion · ICE
Session Churn / Join Stability (separate)
```

## Next

1. Execute Round-2 (≥120s, no hangup)  
2. Classify D / E  
3. Code only after Case E (or later Case B) admission + design review
