# RCA-003 — Presentation Convergence after Recovery

**Status:** **IMPLEMENTED / PENDING FIELD VERIFY** — do **not** mark VERIFIED until gray evidence  
**Date:** 2026-08-11  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**R4:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md)  
**R5 sealed:** [rca-003-r5-failed-media-residency-clear-contract.md](./rca-003-r5-failed-media-residency-clear-contract.md)  
**IC:** [rca-003-ic-uvcp-residency-decoupling.md](./rca-003-ic-uvcp-residency-decoupling.md) · PR [#157](https://github.com/wangy4645/android-decentralized-talkback/pull/157)

## Portfolio status (do not conflate)

```text
WiFi Recovery Protocol       CLOSED / VERIFIED
Delivery Phase-2             CLOSED / VERIFIED
Media Ownership              CLOSED / VERIFIED
Conference Rejoin            CLOSED / VERIFIED

Presentation Projection      IMPLEMENTED / PENDING FIELD VERIFY
Session Churn                OPEN
Roster Projection            OPEN
```

| Round | Result | Doc |
|-------|--------|-----|
| 1–3 | Locate presentation seam (C / E / D-like) | round adj docs |
| 4 | Ownership trace COMPLETE | [R4](./rca-003-r4-conference-media-unavailable-ownership-trace.md) |
| 5 | Residency semantics ACCEPTED | [R5](./rca-003-r5-failed-media-residency-clear-contract.md) |
| IC | UVCP stops mapping FAILED_MEDIA → degraded | [IC](./rca-003-ic-uvcp-residency-decoupling.md) · #157 |

## Sealed answers

```text
R5.1  FAILED_MEDIA = incident residency (not live unavailable)
R5.2  CLEAR = obligationClosed ∧ iceConnected ∧ receivePathLive
      EDGE_RECOVERED not required; no clear while obligation OPEN
R5.3  FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
```

## Architecture split (why not reopen recovery)

```text
Incident State  ──diagnostics/history only──X──► UVCP
Current Media Availability ───────────────────► UVCP ──► Pill
```

> 事故发生过，不代表现在不可用。  
> 当前问题是 UX 状态模型（投影是否代表当前事实），不是「网络恢复了吗」。

## Next

```text
1. merge #157
2. gray install (one device) — no new flap matrix / no Phase-2 re-proof
3. observe Cases 1–3 in IC doc (EDGE_RECOVERED · MEDIA CONNECTED · pill)
4. PASS → RCA-003 close / VERIFIED

Not: new RCA · recovery log hunt · ADR-0030 big rewrite (one-line note in IC only)
```
