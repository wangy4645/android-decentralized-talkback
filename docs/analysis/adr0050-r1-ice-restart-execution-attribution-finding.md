# ADR-0050-R1 — ICE Restart Execution Attribution Finding

**Status:** **FINDING COMPLETE** (log-only on 154011 · product auth 2026-08-11 「授权」)  
**Evidence:** `logs/adr0050-admission-20260811-154011/`  
**Audit entry:** [adr0050-r1-ice-restart-execution-attribution-audit.md](./adr0050-r1-ice-restart-execution-attribution-audit.md)  
**Admission parent:** GATE VERIFIED · Execution was OPEN → now **ATTRIBUTED**

---

## Freeze card

```text
ADR-0050-R1 ICE Restart Execution Attribution

Status: FINDING COMPLETE

Primary (inbound → flapped M02):
  Case 1 — offer sent; remote ingress ABSENT in delivery window
  → answer never closes SDP
  → ICE CHECKING → ATTEMPT_TIMEOUT → FAILED_MEDIA

Contributing (edge M02↔M03):
  Bilateral HOST_RESTART createOffer(iceRestart) ~3s apart
  (lease admits both; lease ≠ arbitration)

Not:
  ICE timeout too short (symptom)
  Admission regression (lease worked)
  UVCP / residency / ownership rewrite
```

---

## A — Offer / answer

| Edge (local→remote) | Offer sent? | Remote ingress in window? | Answer in window? |
|---------------------|-------------|---------------------------|-------------------|
| M01→M02 | YES 15:41:43 (`MEDIA_SIGNAL_OFFER_SENT` / `RECOVERY_OFFER_SENT`) | **NO** — `RECOVERY_REMOTE_INGRESS_ABSENT` @ 15:41:46 + retry @ 15:41:49 | **NO** |
| M03→M02 | YES 15:41:46 | **NO** — `REMOTE_INGRESS_ABSENT` @ 15:41:49 | **NO** |

M02 first `RECOVERY_OFFER_RECEIVED` from M01: **15:42:30** (~47s after first send; after M01 `ATTEMPT_TIMEOUT` ~15:41:56).

```text
Case 1 CONFIRMED (primary for directed inbound edges):

  createOffer + SLD(OFFER) + LOCAL_ACCEPT
        |
        X  remote ingress (WINDOW_DEADLINE)
        |
  no setRemoteDescription / createAnswer on flapped peer in budget
        |
  local ICE CHECKING with stale remoteDesc=ANSWER
        |
  ATTEMPT_TIMEOUT
```

**Not Case 2** (offer+answer then transport stall) for M01→M02 / M03→M02 in the attempt budget.

---

## B — Bilateral restart

| Pair | Observation |
|------|-------------|
| M01↔M02 | M01: `HOST_RESTART` + lease + offer. M02→M01: `PARTICIPANT_REATTACH` **DEFERRED** (`MEDIA_NOT_READY` / `AUTHORITY_LOST`) — **no** dual createOffer on this edge in the critical window. |
| M02↔M03 | **CONFIRMED collision pattern:** M03→M02 offer @ 15:41:46 (`pcGen=2`); M02→M03 offer @ 15:41:49 (`pcGen=3`); both lease-admitted HOST_RESTART. Both also hit `REMOTE_INGRESS_ABSENT`. |

```text
Lease = admission capability
≠ single-writer arbitration for the undirected edge
```

P1 hypothesis **confirmed as contributing** on M02↔M03; **not** the sole explanation for M01→M02 (ingress absent dominates).

---

## C — Stale PC / generation

| Fact | Value |
|------|-------|
| M01→M02 restart | `pcGeneration=2` `transportGeneration=2` (same as pre-flap conference PC) |
| M03→M02 restart | `pcGeneration=2` |
| M02→M03 restart | `pcGeneration=3` |
| After SLD(OFFER) | `localDesc=OFFER` while `remoteDesc=ANSWER` (prior answer) until new answer arrives |

**Verdict:** Restart attaches to **existing** PC (expected for iceRestart). Stale `remoteDesc=ANSWER` is the **symptom of missing answer** (A), not proven independent generation pollution. **C = secondary / not primary.**

---

## Probability reorder (post-evidence)

| Rank | Hypothesis | 154011 |
|------|------------|--------|
| **P2→P1** | Answer / signaling incomplete (`REMOTE_INGRESS_ABSENT`) | **PRIMARY** for directed M01/M03→M02 |
| **P1→P2** | Bilateral restart collision | **CONFIRMED contributing** on M02↔M03 |
| **P3** | Candidate / STUN after answer | **NOT REACHED** (no answer in budget) |

**Successor:** [adr0050-r2-negotiation-episode-arbitration-audit.md](./adr0050-r2-negotiation-episode-arbitration-audit.md) — offerer/answerer + ingress readiness (read-only).

---

## Architecture implication (do not expand wrongly)

```text
Admission (ADR-0050)     VERIFIED — leave frozen
Execution failure class  SIGNALING / REMOTE INGRESS (flap window)
                       + lease-without-arbitration (bilateral on M02↔M03)

Next knife candidates (choose one after product):
  R2a  Delivery / ingress readiness before iceRestart offer
       (or defer offer until remote path live — NOT enlarge timeout)
  R2b  Lease arbitration / single-edge restart writer
       (only if product wants collision knife next)

Forbidden from this finding alone:
  enlarge attempt / ICE timeout
  UVCP / residency / owner rewrite
  reopen ADR-0050 admission semantics
```

---

## One-line freeze

> **Lease correctly admitted restart; the restart offer did not obtain remote ingress (and thus no answer) inside the attempt window — with a concurrent bilateral offer on M02↔M03. Next work is execution signaling / arbitration attribution, not admission or timeout.**
