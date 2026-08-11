# ADR-0050 R2a — Readiness Attribution Audit (desk)

**Status:** **COMPLETE** · Verdict **H2 / CROSS-DOMAIN** · **R2a FROZEN** · 2026-08-11  
**Parent finding:** [adr0050-r2a-field-finding-1.md](./adr0050-r2a-field-finding-1.md) (**FIELD SUPPORTED**)  
**Evidence base:** `logs/adr0050-r2a-ingress-20260811-163820/` (M01→M02 primary)

```text
Goal: attribute READY credibility — CLOSED
R2a predicate: NO CHANGE
R2b: HOLD (FUTURE ONLY)
```

---

## Frozen domain boundary (write into all R2a scoring)

```text
RECOVERY_REMOTE_INGRESS_ABSENT
  is not a negotiation readiness signal.
  It must not be used as R2a admission evidence.
```

> Recovery delivery observation marker 不参与 NegotiationIngressGate 判定。

```text
REMOTE_NEGOTIATION_READY          → negotiation admission (R2a)
RECOVERY_REMOTE_INGRESS_ABSENT      → reattach delivery observation window
```

---

## Board after this audit

```text
ADR-0050 Admission Lease          VERIFIED
R2a Negotiation Ingress Gate       IMPLEMENTED / FIELD SUPPORTED
R2a Readiness Predicate            NO CHANGE
R2b Offer Arbitration              HOLD
```

---

## Authoritative timeline (M01→M02, session `998dde90…`)

| t (local) | Fact |
|-----------|------|
| 16:39:35–38 | attempt=1 `LEASE` → `INGRESS_PENDING` → `DEADLINE` (`lastIngress=NONE`) — **correct refuse** |
| 16:40:04–07 | attempt=1 again PENDING→DEADLINE — still no post-start ingress |
| 16:40:08.276 | `RECOVERY_REATTACH_INBOUND` from M02 |
| 16:40:08.322 | `SLD OFFER` (reattach path) — **before** R2a READY |
| 16:40:08.357 | attempt **1→2** SUPERSEDE `REATTACH_INBOUND` (`startedAtMs=…08356`) |
| 16:40:08.363 | R2a path first blocked: `OFFER_AWAITING_ANSWER` |
| 16:40:08.377+ | inbound `GROUP_ACCEPT` / `WEBRTC_ICE` stamps negotiation-capable ingress |
| 16:40:08.451 | `REMOTE_NEGOTIATION_READY` attempt=2 `trigger=IMMEDIATE` `lastIngressAtMs=…08431` → `ICE_RESTART_DISPATCHED` |
| 16:40:08.489 | `LOCAL_ACCEPT` offerLineageId=**L1** opens **RecoveryIngressObservation** 3s window |
| 16:40:08.582 | `RECOVERY_REATTACH_ACK_RECEIVED` + **`RECOVERY_DELIVERY_CONFIRMED`** (L1) |
| 16:40:11.496 | **`RECOVERY_REMOTE_INGRESS_ABSENT`** L1 `WINDOW_DEADLINE` ← **+3.007s after LOCAL_ACCEPT** |
| 16:40:12.795 | M02: `RECOVERY_REMOTE_INGRESS_OBSERVED` L1 (peer side) |

SDP peer receive (M02 `SRD OFFER` from M01) ≈ **16:40:12.6** (~4s after dispatch) — vs 154011 ~47s.

---

## Q1 — Is READY too weak?

### Hypotheses

| Id | Claim |
|----|-------|
| H1 | R2a READY optimistic → offer into non-receivable SDP ingress |
| H2 | READY OK; ABSENT is delivery / other domain |

### Decision: **H1 NOT CONFIRMED · lean H2 + cross-domain**

Reasons:

1. **Token collision:** field scored `REMOTE_INGRESS_ABSENT`, but producer is `RecoveryIngressObservation` (reattach **delivery ACK** window, `offerLineageId=L1`, 3s) — **not** `NegotiationIngressGate` / R2a READY semantics.
2. **ABSENT after CONFIRMED:** M01 already logged `RECOVERY_DELIVERY_CONFIRMED` at **16:40:08.582**; ABSENT at **16:40:11.496** cannot mean “READY lied about SDP receivable.” Treat as delivery-observation residual (window not closed / identity race) — **out of R2a predicate scope**.
3. **SDP path improved:** peer apply-offer ~4s; not the 154011 black-hole shape.
4. **Residual (not H1 proof):** READY evidence included negotiation-capable types (`GROUP_ACCEPT` / `WEBRTC_ICE` / reattach traffic). Broad vs “SDP processable” remains a **design note**, not confirmed field failure this run.

**Do not authorize R2a predicate tighten from this ABSENT alone.**

---

## Q2 — PENDING exit / episode binding

| Check | Finding |
|-------|---------|
| Event vs sticky | Sticky `lastNegotiationCapableInboundAtMs` on edge record; admit polls `isReady`; observe may complete PENDING |
| Episode bind | `NegotiationIngressGate.isReady` requires `lastAt >= recoveryStartedAtMs` and fresh window; READY log carries `attempt` / `obligationGen` |
| This run | attempt2 `startedAt=…08356`, `lastIngress=…08431` → **post-start** — not pre-attempt stale |
| Attempt1 | DEADLINE with `NONE` — exit condition **correct refuse** |
| Stale risk residual | Ingress stamp **not attempt-labeled**; field survives SUPERSEDE; mitigation = `recoveryStartedAtMs` floor only |
| `READY_EPOCH == OFFER_EPISODE` | **Not explicitly logged**; inferred OK this episode via attempt2 start vs lastIngress |

**Observability gap only** — optional future log (`ingressAttemptId` / clear-on-supersede) **behavior-neutral**; **not** a patch trigger now.

---

## Scoring correction for Finding #1

| Marker | Correct reading |
|--------|-----------------|
| `NEGOTIATION_INGRESS_*` / `REMOTE_NEGOTIATION_READY` | R2a gate |
| `RECOVERY_REMOTE_INGRESS_ABSENT` (L1 / WINDOW_DEADLINE) | Delivery observation — **do not score as R2a fail** |
| M03 PENDING→DEADLINE | Correct refuse — unchanged |

Finding #1 remains **PARTIAL SUCCESS / field verification NOT COMPLETE** only in the sense that a **clean ABSENT=0 + episode-tagged READY** soak was not the instrument; **attribution no longer blocks on H1**.

---

## Decision

```text
Do not modify R2a predicate
Do not start R2b
Do not open delivery-window rewrite from this desk alone
Optional later: field adjudicate script must NOT treat RECOVERY_REMOTE_INGRESS_ABSENT as R2a P0 fail
Optional later: behavior-neutral READY/episode log — only if another field needs it
```

### Exit matrix (resolved)

| Outcome | This audit |
|---------|------------|
| H1 confirmed → predicate IC | **No** |
| H2 / cross-domain → leave R2a | **Yes** |
| Ambiguous → another soak | **Not required for Q1/Q2** |

Next product gate (when wanted): either close Finding #1 field verification with **scoring-corrected** re-adjudicate of same logs, or a **single** narrow soak only if product still wants ABSENT=0 under corrected P0 — **not** to re-ask H1.
