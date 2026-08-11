# ADR-0050-R2 — Negotiation Episode Arbitration Finding

**Status:** **FINDING COMPLETE** (read-only on 154011 · product auth 2026-08-11 「授权 请继续」)  
**Evidence:** `logs/adr0050-admission-20260811-154011/`  
**Audit entry:** [adr0050-r2-negotiation-episode-arbitration-audit.md](./adr0050-r2-negotiation-episode-arbitration-audit.md)  
**R1:** [adr0050-r1-ice-restart-execution-attribution-finding.md](./adr0050-r1-ice-restart-execution-attribution-finding.md)

---

## One-sentence answer

> **Yes** — one recovery episode can have multiple legitimate restart initiators (lease admits both sides), and restart offers are sent **before** the peer has answer-ingress conditions (`REMOTE_INGRESS_ABSENT`; ~47s late receive).

---

## Freeze card

```text
ADR-0050-R2 Negotiation Episode Arbitration

Status: FINDING COMPLETE

R2.1 Dual HOST_RESTART writers / episode   YES (observed M02↔M03)
R2.2 Lease lacks offerer role              YES (permission-only today)
R2.3 Ingress readiness not offer-gated     YES (offer then REMOTE_INGRESS_ABSENT)

Admission (ADR-0050)                       leave FROZEN / PASS
Do NOT: timeout · retry expand · UVCP · owner rewrite · roll back lease
```

---

## R2.1 — May one edge episode admit two HOST_RESTART writers?

**Answer: YES (observed) — violates expected single-initiator posture.**

| Direction | Window | Fact |
|-----------|--------|------|
| M03→M02 | 15:41:46.714 | `createOffer iceRestart` + prior `LEASE_ADMITTED` + `HOST_RESTART` |
| M02→M03 | 15:41:49.072 | `createOffer iceRestart` + `LEASE_ADMITTED` + `HOST_RESTART` |
| Delta | ~3s | Same undirected edge, both OFFERER |

M01↔M02 in the same flap: M01 `HOST_RESTART`+offer; M02→M01 `PARTICIPANT_REATTACH` **DEFERRED** (no dual createOffer on that directed pair). Dual-writer pathology is **edge-asymmetric** but **confirmed** on M02↔M03.

```text
Expected:  edge + episode → single initiator
Observed:  lease permission on both halves → two OFFERERs
```

---

## R2.2 — Should lease carry offerer identity / role?

**Answer: YES as design gap (recommendation) — not an impl authorization.**

Today:

```text
lease = permission to ICE restart
≠ who is OFFERER vs ANSWERER this episode
```

154011 shows permission without role → both sides may press offer.

Candidate (INV-1 preserved):

```text
lease = { edge, episode, holder, role=OFFERER }
```

= episode offer-writer only · **not** `canonicalNegotiationOwner` transfer.

---

## R2.3 — Should peer ingress readiness gate offer after lease?

**Answer: YES as design gap — strongly evidenced.**

| t | Event |
|---|--------|
| 15:41:43.650 | M01 `MEDIA_SIGNAL_OFFER_SENT` → M02 |
| 15:41:46 / 49 | `RECOVERY_REMOTE_INGRESS_ABSENT` (WINDOW_DEADLINE) |
| 15:41:56 | M01 attempt timeout path |
| 15:42:30.605 | M02 first `RECOVERY_OFFER_RECEIVED` from M01 (~**47s** late) |

```text
Today:   lease admitted → offer
Needed:  lease admitted → peer ingress ready? → offer

Late offer ≠ slow recovery; negotiation episode already dead.
```

---

## Coupling (do not pick one knife blindly)

```text
Ingress absent alone     → Case 1 answer missing (R1 primary on M01/M03→M02)
Dual OFFERER alone       → glare / collision risk (R1 contributing on M02↔M03)
Together                 → offer into black hole + competing writers
```

**Product sequencing (ACCEPTED 2026-08-11):** [adr0050-r2a-r2b-sequencing-decision.md](./adr0050-r2a-r2b-sequencing-decision.md)

| Order | Knife | Why first/second |
|-------|-------|------------------|
| **1 R2a** | Ingress readiness before offer | Clears primary failure even with one offerer |
| **2 R2b** | Episode single offerer (not “owner”) | After R2a; only if dual writers still hurt |

**Forbidden alone:** enlarge timeout · expand retry · roll back 0050.

---

## Portfolio after R2

```text
Negotiation Admission     PASS (frozen)
R1 Execution attribution  COMPLETE
R2 Arbitration audit      FINDING COMPLETE
Impl                      NOT AUTHORIZED (await product knife choice)
```
