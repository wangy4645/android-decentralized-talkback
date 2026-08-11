# ADR-0050 R2a — Field Finding #1

**Status:** **PARTIAL SUCCESS** · Field verification **NOT COMPLETE** · 2026-08-11  
**Evidence:** `logs/adr0050-r2a-ingress-20260811-163820/`  
**Contrast:** `logs/adr0050-admission-20260811-154011/` (~47s late / `REMOTE_INGRESS_ABSENT`)  
**Run card:** [adr0050-r2a-directed-ingress-soak-run-card.md](./adr0050-r2a-directed-ingress-soak-run-card.md)  
**Adjudication parent:** [adr0050-r2a-architecture-adjudication.md](./adr0050-r2a-architecture-adjudication.md)

```text
R2a Negotiation Ingress Gate

Implementation: VERIFIED
Field behavior: PARTIAL IMPROVEMENT
Field verification: NOT COMPLETE

R2b Offer Arbitration: HOLD
```

**Do not write:** PASS · FAIL · R2a VERIFIED (field) · open R2b · patch now.

---

## Result (compressed)

```text
R2a Field Finding #1

Result:
PARTIAL SUCCESS

Evidence:
- Admission→Ingress→Dispatch 链路生效
- M01→M02 offer latency 从 ~47s 降至 ~4s
- NON_OWNER_BLOCKED=0

Open:
- REMOTE_INGRESS_ABSENT 未归零
- M03 无 ingress readiness（正确拒发，非 R2a 失败）

Decision:
Do not modify R2a yet
Do not start R2b
Run one focused readiness attribution round
```

**One line:** R2a 已证明「不要盲发 offer」正确，但尚未证明「READY ⇒ offer 一定可被接收」。下一步钉死 READY 可信度，不扩架构。

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
