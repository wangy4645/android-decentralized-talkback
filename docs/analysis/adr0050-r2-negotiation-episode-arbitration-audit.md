# ADR-0050-R2 — Negotiation Episode Arbitration Audit

**Status:** **FINDING COMPLETE** — [adr0050-r2-negotiation-episode-arbitration-finding.md](./adr0050-r2-negotiation-episode-arbitration-finding.md)  
**Name (frozen):** Negotiation Episode Arbitration Audit  
**(Not** timeout · **Not** retry enlargement · **Not** ownership transfer · **Not** UVCP)  
**Parent:** [0050-negotiation-admission-handoff.md](../adr/0050-negotiation-admission-handoff.md)  
**R1 finding:** [adr0050-r1-ice-restart-execution-attribution-finding.md](./adr0050-r1-ice-restart-execution-attribution-finding.md) (**FINDING COMPLETE**)  
**Seed evidence:** `logs/adr0050-admission-20260811-154011/`

---

## Architect freeze (2026-08-11)

```text
ADR-0050 Phase-1/2     SUCCESS (admission boundary complete)
  NON_OWNER_BLOCKED    = 0
  LEASE_ADMITTED       = yes
  ICE_RESTART_DISPATCHED = yes
  INV-1 owner rewrite  = none

Do NOT revisit / roll back lease semantics.

Problem moved from control-right → negotiation closure reliability:

  OFFER SENT
      |
      X
  ANSWER missing  (+ late ingress ~47s)
  + bilateral HOST_RESTART on M02↔M03
```

| Layer | Status |
|-------|--------|
| Recovery Last-mile | PASS |
| Media Ownership | PASS |
| Negotiation Admission | PASS (leave frozen) |
| Negotiation Execution | **OPEN** — ingress readiness · offerer arbitration · answer closure |

**Correct reading:** lease did its job; it exposed the real negotiation-layer gap.

---

## Missing control (hypothesis — audit must confirm)

```text
Have today:
  Media Action Ownership
        ↓
  Negotiation Lease          (= permission to press restart)
        ↓
  ICE restart dispatch

Missing:
  Negotiation Lease
        ↓
  Who is OFFERER this episode?
  Who must ANSWER?
```

Permission ≠ conversation process ownership for one edge episode.

---

## Three questions only (R2)

### R2.1 — May one edge episode admit two HOST_RESTART writers?

**Expected posture (to validate):** **NO**

```text
edge + episodeId + negotiationAttempt → single initiator
```

Evidence seed: M02↔M03 both lease + `createOffer(iceRestart)` ~3s apart (154011).

### R2.2 — Should lease carry offerer identity / role?

Today: `lease = permission` only.

Candidate shape (design discussion only — **not** impl):

```text
lease = { edge, episode, holder, role=OFFERER }
```

**INV-1 preserved:** not long-term `canonicalNegotiationOwner` transfer — only **this episode’s offer writer**.

### R2.3 — Should peer ingress readiness gate offer send after lease?

Today:

```text
lease admitted → offer
```

Candidate:

```text
lease admitted → peer ingress ready? → offer
```

Else: flap recovery → instant offer → peer UDP ingress not up → **REMOTE_INGRESS_ABSENT** / 47s late packet = dead negotiation episode.

---

## Mode / freeze

```text
Mode:  READ-ONLY audit (docs + existing logs; optional annotated re-read)
No:    code · timeout enlarge · retry expand · ICE strategy · UVCP · residency
No:    roll back ADR-0050 · rewrite negotiationOwner · new recovery phases
```

**One sentence to answer before any impl:**

> In one recovery episode, are there multiple legitimate restart initiators, and/or is restart offered before the peer has answer-ingress conditions?

If **yes** → separate product auth for next knife (R2a ingress vs R2b arbitration — or combined ADR).  
If **no** → reclassify before coding.

---

## Auth gate

```text
DRAFTED → product auth 「授权 请继续」 → FINDING COMPLETE on 154011
  R2.1 YES dual HOST_RESTART / episode (M02↔M03)
  R2.2 YES lease lacks offerer role (design gap)
  R2.3 YES ingress not gated before offer (47s late receive)
  → impl only after separate knife choice (R2a / R2b / sequenced)
```
