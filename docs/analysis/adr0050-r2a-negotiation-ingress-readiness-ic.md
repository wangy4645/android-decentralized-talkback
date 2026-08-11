# ADR-0050 R2a — Negotiation Ingress Readiness IC

**Status:** **AUTHORIZED** (product / architect 2026-08-11) · **IC only — no code yet**  
**Sequencing:** [adr0050-r2a-r2b-sequencing-decision.md](./adr0050-r2a-r2b-sequencing-decision.md) (**R2a → R2b**)  
**Parents:** R2 finding · R1 finding · ADR-0050 admission **CLOSED / VERIFIED**

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

## Phased work

| Phase | Work | Auth |
|-------|------|------|
| **IC (now)** | This doc — states · probe semantics · gate site · bounds · foreshadow | **AUTHORIZED** |
| **Patch** | Minimal gate in `issueBoundedIceRestart` path | Separate auth |
| **Field** | Chain above on M02 flap; compare to 154011 `REMOTE_INGRESS_ABSENT` | After patch |
| **R2b** | Restart Offer Arbitration — only if dual offerer still hurts post-R2a | After R2a field |

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
