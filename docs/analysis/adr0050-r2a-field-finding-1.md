# ADR-0050 R2a — Field Finding #1

**Status:** **FIELD SUPPORTED** · Attribution **CLOSED** · 2026-08-11  
**Evidence:** `logs/adr0050-r2a-ingress-20260811-163820/`  
**Contrast:** `logs/adr0050-admission-20260811-154011/` (~47s late)  
**Run card:** [adr0050-r2a-directed-ingress-soak-run-card.md](./adr0050-r2a-directed-ingress-soak-run-card.md)  
**Adjudication parent:** [adr0050-r2a-architecture-adjudication.md](./adr0050-r2a-architecture-adjudication.md)  
**Attribution:** [adr0050-r2a-readiness-attribution-audit.md](./adr0050-r2a-readiness-attribution-audit.md)

```text
R2a Negotiation Ingress Gate

Implementation: VERIFIED
Field: FIELD SUPPORTED
Readiness Predicate: NO CHANGE

R2b Offer Arbitration: HOLD (FUTURE ONLY)
```

**Do not write:** generic PASS/FAIL soak label · open R2b from pre-R2a dual-restart · predicate patch.

---

## Result (compressed)

```text
R2a Field Finding #1

Result:
FIELD SUPPORTED

Evidence:
- attempt1: LEASE→PENDING→DEADLINE→NO DISPATCH (correct block)
- attempt2: PENDING→READY→DISPATCH→ANSWER (~4s vs 154011 ~47s)
- NON_OWNER_BLOCKED=0

Domain correction:
- RECOVERY_REMOTE_INGRESS_ABSENT = delivery observation (not R2a readiness)
- DELIVERY_CONFIRMED precedes ABSENT on M01→M02 — cannot indict READY

Decision:
R2a predicate FROZEN (no patch)
R2b HOLD
No further R2a soak unless R2b trigger
```

**Attribution:** [adr0050-r2a-readiness-attribution-audit.md](./adr0050-r2a-readiness-attribution-audit.md) (**CLOSED**)

**One line:** R2a 正确阻断不可发送窗口 + 正确放行可发送窗口；`RECOVERY_REMOTE_INGRESS_ABSENT` 不得再当作 R2a 失败证据。

---

## Why direction is correct (not PASS)

154011:

```text
OFFER_SENT → REMOTE_INGRESS_ABSENT → ~47s receive
```

本轮 (M01→M02):

```text
LEASE_ADMITTED → INGRESS_PENDING → READY → DISPATCH → ~4s peer receive
```

R2a **changed offer timing**. Prior main path「ingress 未建立仍发 restart offer」不再是主路径。

Acceptance target is **not** “faster than before”, but:

> offer 不进入无 ingress 的窗口

That target is **not closed**.

---

## Open A — `REMOTE_INGRESS_ABSENT` still once

Implies `READY ≠ absolute ready` **or** delivery race after correct READY.

| Hypothesis | Shape | Implication |
|------------|-------|-------------|
| H1 | READY 偏乐观；ingress 未稳 | tighten readiness predicate |
| H2 | READY 正确；transport/delivery race | R2a OK; delivery window domain |

**Evidence insufficient — do not choose.** No R2a patch until attribution.

---

## Open B — M03→M02 (cleaner than M01)

```text
LEASE → PENDING → DEADLINE → no READY → no OFFER
```

Classification:

```text
R2a gate correctly refused dispatch
```

**Not** an R2a failure. R2a only fixes “peer already ingress-capable but local offered too early”; it does **not** create peer ingress.

---

## Why R2b stays HOLD

No new dual-offerer evidence this run:

- M03: no READY / no OFFER
- M01: one offer path

Do not fold “offer too early” with “offer too many”.

---

## Next (no code): readiness attribution audit

Answer only:

### Q1 — Is READY too weak?

Compare `REMOTE_NEGOTIATION_READY` timestamp vs offer-received / `REMOTE_INGRESS_ABSENT`. Short READY→absent gap → optimistic window.

### Q2 — Is PENDING exit correct?

READY: one-shot event vs sticky state? Episode/epoch/generation bound? Risk: old-episode READY pollutes new episode.

### Field delta (narrow only)

Add equivalence of:

```text
READY_EPOCH == OFFER_EPISODE
```

(or log proving READY belongs to **current** recovery episode — not stale signaling.)

**Do not** enlarge metrics / DEGRADED / EDGE_RECOVERED / R2b.

---

## Operator note

Full media recovery observed at **~1m28s** — recorded as overall media fact; **not** scored as R2a field PASS.
