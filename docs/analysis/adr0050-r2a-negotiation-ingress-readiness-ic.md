# ADR-0050 R2a — Negotiation Ingress Readiness IC

**Status:** **IC FROZEN** · **PATCH ACCEPT** (PR [#167](https://github.com/wangy4645/android-decentralized-talkback/pull/167)) · Field **AUTHORIZED after merge** · R2b **HOLD**  
**Adjudication:** [adr0050-r2a-architecture-adjudication.md](./adr0050-r2a-architecture-adjudication.md)  
**Sequencing:** [adr0050-r2a-r2b-sequencing-decision.md](./adr0050-r2a-r2b-sequencing-decision.md) (**R2a → R2b**)  
**Parents:** R2 finding · R1 finding · ADR-0050 admission **CLOSED / VERIFIED**

```text
ADR-0050 Admission Lease          VERIFIED
R2a Negotiation Ingress Gate       IMPLEMENTED (待 field)
R2b Offer Arbitration              NOT STARTED / HOLD
```

---

## Architect tightening (accepted)

```text
R2a IS:   avoid emitting an unrecoverable restart offer into a known
          non-receivable negotiation window (bounded execution gate)

R2a IS NOT:
  - “wait until peer ready, then restart” (unbounded sync)
  - new recovery orchestration
  - wait for ICE CONNECTED / media ready / EDGE_RECOVERED
  - heartbeat / HELLO reuse
```

**One line:** 协商入口门闩，不是新的 recovery 编排器。

---

## Problem position (frozen)

```text
Lease admission        ✅
ICE restart dispatchable ✅
Remote ingress ready   ❌  ← R2a
Answer / EDGE_RECOVERED    downstream
```

---

## Wrong vs right shape

### Wrong (forbidden)

```text
lease acquired
  → block until remoteReady=true forever
  → send offer
```

Risks: A↔B deadlock · uncontrollable recovery latency · fake “orchestration”.

### Right (IC target)

```text
lease acquired
  → if ingress confidence already high: send offer immediately
  → else: enter BOUNDED negotiation wait
        observe REMOTE_NEGOTIATION_READY
        if ready → send offer
        if deadline → existing recovery failure path (no new terminal class)
```

Keyword: **bounded**.

---

## Minimal states (only)

```text
LEASE_ADMITTED
      ↓
NEGOTIATION_INGRESS_PENDING     // optional; skip if already ready
      │
      ├── REMOTE_NEGOTIATION_READY → OFFER_SENT
      │
      └── deadline → existing failure path
            (ATTEMPT_TIMEOUT / FAILED_MEDIA residency — do not invent new fail class)
```

**Do not invent:** `WAITING_FOR_PEER_RECOVERY` · `WAITING_FOR_MEDIA_READY` · `WAITING_FOR_NETWORK_STABLE`.

---

## `REMOTE_NEGOTIATION_READY` semantics

> Peer signal **ingress** can receive and process a restart offer.

| Must NOT mean | Why |
|---------------|-----|
| ICE CONNECTED | too late |
| MEDIA CONNECTED | wrong layer |
| EDGE_RECOVERED | result, not precondition |
| heartbeat alive | ≠ SDP processable |
| HELLO / discovery | discovery ≠ negotiation ingress |

**Open for impl design (not decided in IC):** concrete probe source (e.g. recent inbound negotiation-domain datagram / capability bit). Must stay **negotiation capability**, not media usability.

---

## Gate location (patch shape — later auth)

**Touch only:** path after lease admit in `issueBoundedIceRestart` (or immediate callee before `createOffer`).

```text
before: lease admitted → createOffer
after:  lease admitted → ingress check (immediate or bounded) → createOffer
```

**No-touch:** ownership · lease permission INV-1..3 · ICE strategy · UVCP · residency · retry policy · timeout budget enlargement · R2b arbitration logic.

---

## Interface foreshadow (no R2b impl)

Prefer episode-scoped request shape over bare `restart()` so R2b can plug later without re-architecting:

```text
requestIceRestart(episode)
  // future: EpisodeCoordinator may selectOfferer → lease → ingress → send
```

R2a must **not** implement coordinator; only leave the call site / episode key clean.

---

## Observability (field success — not pill/DEGRADED)

```text
LEASE_ADMITTED
  → NEGOTIATION_INGRESS_PENDING   (if wait entered)
  → REMOTE_NEGOTIATION_READY
  → ICE_RESTART_DISPATCHED / OFFER_SENT
  → ANSWER_RECEIVED
  → EDGE_RECOVERED
```

Also log: `NEGOTIATION_INGRESS_DEADLINE` when falling to existing failure path (prove bounded, not hung).

---

## Static invariants (INV-R2a — architecture ACCEPT)

### INV-R2a-1 — lease before offer

```text
LEASE_ADMITTED  before  createOffer / RECOVERY_ICE_RESTART_DISPATCHED
```

Cannot reverse.

### INV-R2a-2 — ready is negotiation-only

```text
REMOTE_NEGOTIATION_READY  only  allows offer dispatch after lease
```

Must **not** substitute: `ICE_CONNECTED` · `HELLO` · `HEARTBEAT` · `EDGE_RECOVERED` · media ready.

### INV-R2a-3 — deadline is not a new terminal

```text
NEGOTIATION_INGRESS_DEADLINE  ≠  FAILED_MEDIA class
```

Deadline clears the ingress wait and leaves the attempt to the **existing** timeout / failure attribution path.

---

## Engineering freeze (2026-08-11 · architecture ACCEPT)

```text
ADR-0050 Admission / Lease     VERIFIED — do not reopen
R2a IC                         FROZEN
R2a patch                      ACCEPT — merge then field
R2b                            HOLD — only if dual legitimate OFFER after R2a field
Field / flap                   AUTHORIZED after merge (narrow markers; not UI DEGRADED)
```

**Cadence:** static INV review → **merge #167** → **one** directed field soak → decide R2b **only** on dual-offer evidence.

**Field success (narrow — prove timing, not episode close):**

```text
LEASE_ADMITTED
  → NEGOTIATION_INGRESS_PENDING (optional)
  → REMOTE_NEGOTIATION_READY
  → OFFER_SENT
  → ANSWER (bounded window)
```

| Marker | Expectation |
|--------|-------------|
| `REMOTE_INGRESS_ABSENT` | 降低 / 消失 |
| `NEGOTIATION_INGRESS_PENDING` | 可出现 |
| `REMOTE_NEGOTIATION_READY` | 出现 |
| OFFER→ANSWER latency | 降低 |

**Do not score R2a field on:** UI DEGRADED · `EDGE_RECOVERED` alone · “recovery 是否完全收敛”.

**Counterfactual vs 154011:**

```text
OLD: OFFER_SENT → REMOTE_INGRESS_ABSENT → ~47s late receive
NEW: REMOTE_NEGOTIATION_READY → OFFER_SENT → answer in bounded window
```

**R2b trigger (HOLD until):** after R2a field, still observe **two legitimate OFFERs** in one episode (SDP collision / answer ambiguity). Do not fold “offer too early” with “offer too many”.

**Review checklist:** gate after lease / before createOffer · ready ≠ ICE/media/EDGE_RECOVERED/heartbeat/HELLO · bounded wait · deadline → existing failure only · no R2b in R2a.

---

## Forbidden

```text
❌ unbounded wait for remoteReady
❌ enlarge attempt / ICE timeout as substitute
❌ expand offer retry
❌ UVCP / residency / owner rewrite
❌ roll back ADR-0050
❌ grow into recovery orchestration / multi-edge coordinator in R2a
```
