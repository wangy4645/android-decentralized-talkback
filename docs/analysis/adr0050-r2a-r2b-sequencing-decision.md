# ADR-0050 — R2a→R2b Sequencing Decision

**Status:** **ACCEPTED** (product / architect 2026-08-11)  
**Parents:**  
- [adr0050-r2-negotiation-episode-arbitration-finding.md](./adr0050-r2-negotiation-episode-arbitration-finding.md)  
- [adr0050-r1-ice-restart-execution-attribution-finding.md](./adr0050-r1-ice-restart-execution-attribution-finding.md)  
**Admission:** [0050-negotiation-admission-handoff.md](../adr/0050-negotiation-admission-handoff.md) — **CLOSED / VERIFIED** (do not reopen)

---

**Verdict**

```text
ADR-0050 Admission         CLOSED / VERIFIED
R2 Finding                 VALID
NEXT                       R2a → R2b
PRIORITY                   ingress readiness first, then single offerer
R2a IC                     AUTHORIZED (bounded gate — not unbounded wait)
R2a patch / R2b            NOT AUTHORIZED
```

**One line:** ADR-0050 solved “who may knock”; R2 shows “door not open yet” — fix door state (R2a) before multi-knocker arbitration (R2b).

---

## Why R2a before R2b

| Finding | Class | Hardness on 154011 |
|---------|-------|--------------------|
| Ingress ungated → `REMOTE_INGRESS_ABSENT` / ~47s late | **Execution precondition** | Primary on M01/M03→M02 |
| Dual HOST_RESTART offerers | **Episode arbitration** | Contributing on M02↔M03 |

Even with a **single** offerer, offer-into-black-hole still fails. R2b alone cannot clear the primary failure mode.

```text
lease admitted → createOffer          // today
lease admitted → ingress ready → offer // R2a target
then (if still dual writers) → R2b
```

---

## R2a scope (IC authorized; patch not yet)

**IC:** [adr0050-r2a-negotiation-ingress-readiness-ic.md](./adr0050-r2a-negotiation-ingress-readiness-ic.md)

```text
IN:   bounded gate before createOffer after lease
        REMOTE_NEGOTIATION_READY = peer can receive restart offer
        (NOT ICE/media/EDGE_RECOVERED/heartbeat/HELLO)
        deadline → existing failure path (no infinite wait)
OUT:  ownership · lease INV semantics · retry · timeout enlarge · UVCP · R2b
SHAPE: 协商入口门闩 — not recovery orchestration
```

## R2b scope (after R2a field)

```text
Name: Restart Offer Arbitration / Episode Negotiation Coordinator
      (NOT “owner” — avoid MediaActionOwner / NegotiationOwner / Lease collision)
IN:   one active offerer per recovery episode among admitted actors
OUT:  canonicalNegotiationOwner rewrite · media-action supersede rewrite
```

---

## Forbidden (frozen)

```text
❌ enlarge timeout
❌ expand offer retry (amplifies multi-offerer + SDP gens)
❌ UVCP / residency
❌ roll back ADR-0050
```

---

## Future stack (target shape)

```text
Recovery Episode
      Lease (permission)           // 0050 — CLOSED
        ↓
Ingress readiness                  // R2a — NEXT
        ↓
Offer arbitration                  // R2b — AFTER R2a
        ↓
createOffer → Answer → ICE
```
