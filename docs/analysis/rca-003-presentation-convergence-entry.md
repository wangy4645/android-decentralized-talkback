# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN (impl) — **R5.1–R5.3 ACCEPTED** · **IC UVCP residency decoupling AUTHORIZED**  
**Date:** 2026-08-11  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**R4:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md)  
**R5 sealed:** [rca-003-r5-failed-media-residency-clear-contract.md](./rca-003-r5-failed-media-residency-clear-contract.md)  
**IC:** [rca-003-ic-uvcp-residency-decoupling.md](./rca-003-ic-uvcp-residency-decoupling.md)

| Round | Result | Doc |
|-------|--------|-----|
| 1–3 | Locate presentation seam (C / E / D-like) | round adj docs |
| 4 | Ownership trace COMPLETE | [R4](./rca-003-r4-conference-media-unavailable-ownership-trace.md) |
| 5 | Residency semantics ACCEPTED | [R5](./rca-003-r5-failed-media-residency-clear-contract.md) |
| IC | UVCP stops mapping FAILED_MEDIA → degraded | [IC](./rca-003-ic-uvcp-residency-decoupling.md) |

## Sealed answers

```text
R5.1  FAILED_MEDIA = incident residency (not live unavailable)
R5.2  CLEAR = obligationClosed ∧ iceConnected ∧ receivePathLive
      EDGE_RECOVERED not required; no clear while obligation OPEN
R5.3  FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
```

## Next

```text
IC UVCP residency decoupling (in flight)
  → unit / state-matrix green
  → gray install (sticky degraded check)
Not: new RCA · field soak · recovery/ICE/Phase-2 · early residency clear
```
