# ADR-0050 R2a — Architecture Adjudication

**Status:** **ACCEPT** · **#167 MERGED** · Field soak **AUTHORIZED** · 2026-08-11  
**Patch:** PR [#167](https://github.com/wangy4645/android-decentralized-talkback/pull/167) → `main` @ `40a984c`  
**IC:** [adr0050-r2a-negotiation-ingress-readiness-ic.md](./adr0050-r2a-negotiation-ingress-readiness-ic.md)  
**Run card:** [adr0050-r2a-directed-ingress-soak-run-card.md](./adr0050-r2a-directed-ingress-soak-run-card.md)

---

## Board

```text
ADR-0050 Admission Lease          VERIFIED
R2a Negotiation Ingress Gate       MERGED (#167) · field AUTHORIZED
R2b Offer Arbitration              NOT STARTED / HOLD
```

```text
#167 merge:     YES (done)
field soak:     AUTHORIZED — Directed R2a (narrow)
R2b:            HOLD
```

**Field goal (only):** lease 后 offer 不进入对端尚无 negotiation ingress 的窗口。  
**Score:** `REMOTE_INGRESS_ABSENT` ↓ · LEASE → READY → OFFER → ANSWER · T1/T2/T3  
**Do not score:** `EDGE_RECOVERED` · DEGRADED · UI  

---

## Why ACCEPT

- Correct problem: **发起资格 ≠ 发送时机**（lease ≠ ingress ready）.
- Layering preserved: Admission → Ingress readiness → Offer — **no new recovery orchestration**.
- `REMOTE_NEGOTIATION_READY` not substituted by ICE / HELLO / HEARTBEAT / EDGE_RECOVERED / media.
- Deadline does **not** invent a new terminal; failure attribution stays on existing attempt path.
- HELLO/HEARTBEAT excluded from ready evidence.

---

## Residual risk (not R2a)

After R2a, multiple nodes may still:

```text
lease OK → ingress OK → createOffer()
```

→ dual OFFER / SDP collision = **R2b**. R2a field success ≠ recovery closed loop.

---

## Field proof goal (narrow)

> Lease 后的 offer **不再进入**对端尚未具备 negotiation ingress 的窗口。

Not: DEGRADED 消失 · EDGE_RECOVERED 必过.

If after R2a: `REMOTE_INGRESS_ABSENT = 0` but ICE restart still does not converge → then R2b **or** deeper answer path — **not** enlarge R2a.
