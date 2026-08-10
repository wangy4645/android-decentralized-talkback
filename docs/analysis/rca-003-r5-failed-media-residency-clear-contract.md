# RCA-003-R5 — Failed Media Residency Clear Contract

**Status:** OPEN — **design only** · Implementation **NOT AUTHORIZED**  
**Date:** 2026-08-11  
**Kind:** Residency Clear Design (small contract track — not a WiFi RCA reopen)  
**Upstream:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md) **COMPLETE**  
**Parents (read-only):** [ADR-0045](../adr/0045-post-obligation-failed-media-residency-clear-admission.md) · [ADR-0044](../adr/0044-user-visible-connectivity-semantics-media-residency.md) · [ADR-0030](../adr/0030-presence-projection-contract.md)

## Freeze before design

```text
RCA-003 R4 COMPLETE

Next:
RCA-003 R5
Failed Media Residency Clear Contract

Scope:
  clear / invalidation semantics
  FAILED_MEDIA residency lifecycle meaning

Not:
  UI / Meeting pill rewrite
  WiFi recovery last-mile
  ICE restart policy
  Retry / timeout budget
  Phase-2 delivery / ownership / acceptance
```

```text
Recovery · Delivery · Media ownership · Conference accept  = PASS
Remaining = FAILED_MEDIA residency lifecycle vs current health projection
```

## Problem (one sentence)

> Why can a connection that is already restored still let `FAILED_MEDIA` history drive the user-visible degraded view?

## Known behavior (from R3/R4 — do not redispute)

```text
attempt_timeout (+ MEMBERSHIP_CONVERGENCE_*)
        |
        v
FAILED_MEDIA_RECOVERY          ← SET residency
        |
        v
mediaUnavailablePeer=true
        |
        v
pill degraded

Meanwhile (can already be true):
ICE CONNECTED · receivePath LIVE · MEDIA CONNECTED · (host) EDGE_RECOVERED

Clear today (ADR-0045):
obligation closed ∧ iceConnected ∧ receivePathLive
→ FAILED_MEDIA_RESIDENCY_CLEARED

Stuck mode (R2):
CLEAR_HELD when receivePathLive=false after deadline
```

---

## Q1 — What is the semantics of FAILED_MEDIA residency?

**Open decision (must pick one wording):**

| Option | Meaning | UI implication |
|--------|---------|----------------|
| **Current-unavailable** | Peer media is not usable *now* | Fair to drive `mediaUnavailablePeer` |
| **Incident / history** | Last recovery attempt failed / observation window | Must **not** alone mean current health |

Observed conflict: residency can assert while media is already CONNECTED → behaves like **incident**, UI treats as **current health**.

**Design output required:** one normative sentence:

```text
FAILED_MEDIA_RECOVERY means: _______________
It does / does not mean current path unusable.
```

---

## Q2 — Who owns clear? Should EDGE_RECOVERED be required?

**Current owner (frozen fact):** `RecoveryResidencyClearPolicy` (ADR-0045).

**Current predicate:**

```text
obligationClosed ∧ iceConnected ∧ receivePathLive
```

**Open:** add `EDGE_RECOVERED` (or equivalent recovery terminal) as:

| Choice | Effect |
|--------|--------|
| **Required** | Stronger coupling to recovery episode success; may delay clear when media self-heals before terminal stamp |
| **Accelerator only** | May admit earlier clear when terminal exists; does not replace E4 |
| **Not required** | Keep ADR-0045 as-is; solve via Q3 decoupling instead |

**Sub-question:** if ICE+receivePath healthy but obligation still OPEN, may residency clear **early**?

```text
Early clear while obligation OPEN?   YES / NO / ONLY IF <predicate>
```

R5 must answer explicitly — no silent “sometimes.”

---

## Q3 — Decouple FAILED_MEDIA from mediaUnavailable?

**Candidate model (preferred direction for discussion — not accepted):**

```text
RecoveryIncidentState
  └── FAILED_MEDIA (history / diagnostics)

MediaAvailabilityState
  └── AVAILABLE / UNAVAILABLE   ← current health only

UserVisibleConnectivityProjection
  └── consumes MediaAvailabilityState only
  └── does NOT consume incident history
```

Analogy: “engine fault light history ≠ engine is broken now.”

**If accepted:** ADR-0030 `mediaUnavailable(P) ⇔ failed-media residency` needs an **amendment path** (new ADR or ADR-0045/0030 clarification) — not a casual UVCP hide.

**If rejected:** keep coupling; tighten clear/invalidation only (Q2).

---

## Suggested sequence (no code until IC)

```text
R5.1  Define FAILED_MEDIA residency lifecycle meaning (Q1)
        |
R5.2  Freeze clear predicate (Q2) — EDGE_RECOVERED? early clear?
        |
R5.3  Decide decoupling of mediaUnavailable projection (Q3)
        |
Implementation Candidate (separate auth)
```

## Explicit non-goals

```text
Add timeouts / retries
Change pill strings without fact change
Reopen WiFi recovery / ICE / delivery
UVCP-hide DEGRADED while residency still true (forbidden inversion)
Absorb into ADR-0038 completion / markRecovered
```

## Relation to ADR-0045

ADR-0045 already owns **post-obligation** clear admission.  
R5 asks whether **meaning** of residency and **coupling** to UVCP still match field (CONNECTED + residency → degraded).  
Any predicate/decoupling change is an **amendment or successor ADR**, not an informal patch.

## Exit criteria for R5

```text
1. Normative answer to Q1 (one sentence)
2. Clear predicate table (incl. EDGE_RECOVERED / early-clear YES|NO)
3. Q3 ACCEPT or REJECT with ADR impact named
4. Implementation Candidate doc only after 1–3 sealed
```
