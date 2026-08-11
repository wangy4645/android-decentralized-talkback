# ADR-0050-R1 — ICE Restart Execution Attribution Audit

**Status:** **DRAFTED** · Field attribution **NOT STARTED** (await product auth)  
**Name (frozen):** ICE Restart Execution Attribution  
**(Not** a recovery redesign · **Not** timeout enlargement · **Not** RNA Directed #5)  
**Parent:** [0050-negotiation-admission-handoff.md](../adr/0050-negotiation-admission-handoff.md)  
**Admission field:** `logs/adr0050-admission-20260811-154011/` — **Admission PASS / Execution OPEN**  
**Run card (admission):** [adr0050-directed-admission-validation-run-card.md](./adr0050-directed-admission-validation-run-card.md)  
**Field result:** `logs/adr0050-admission-20260811-154011/FIELD_RESULT.md`

---

## Portfolio freeze (architect 2026-08-11)

```text
Delivery              CLOSED
Media Ownership       CLOSED
Negotiation Admission CLOSED (ADR-0050 gate FIELD-VERIFIED)
ICE Restart Execution OPEN  ← this audit
Completion            NOT REACHED
Presentation          CLOSED (RCA-003)
```

**Correct reading of 154011:**

> ADR-0050 fixed **whether recovery may be initiated**.  
> What remains is **whether the initiated ICE restart can complete**.

```text
Admission layer        PASS
Execution layer        OPEN
Completion layer       NOT REACHED
```

**Do not say:** “ADR-0050 had no effect.”  
**Do say:** lease admit → `createOffer(iceRestart)` → CHECKING → timeout — progress past NON_OWNER_BLOCKED.

---

## Entry condition (only run when all hold)

```text
NEGOTIATION_LEASE_ADMITTED = true
ICE_RESTART_DISPATCHED     = true
EDGE_RECOVERED             = false
```

Seed episode: `logs/adr0050-admission-20260811-154011/` (M01/M03 → M02).

---

## Three questions only

### A — Offer accepted by peer?

```text
LOCAL:  createOffer → setLocalDescription → send offer
REMOTE: receive offer? → setRemoteDescription? → createAnswer? → send answer?
```

| Case | Pattern | Layer |
|------|---------|-------|
| 1 | offer sent, **answer missing** | signaling / remote busy / remote admission |
| 2 | offer + answer, ICE CHECKING → timeout | candidate / connectivity |

### B — Bilateral restart collision?

Lease = admission, **not** arbitration.

```text
M01/M03: HOST_RESTART + lease → createOffer(iceRestart)
M02:     PARTICIPANT_REATTACH / own restart epoch?
```

If both offer iceRestart in same window → offer collision → CHECKING forever is **P1 hypothesis**.

### C — Stale PC / generation pollution?

```text
lease admitted → restart attached to old PeerConnection / old candidates?
```

Observe: `pcGeneration` / `transportGeneration` / signaling role at `ICE_RESTART_REQUESTED`.

---

## Probability order (hypothesis only — audit must confirm)

| Rank | Hypothesis | Why |
|------|------------|-----|
| **P1** | Bilateral restart collision | Prior dual-role history; lease unlocked both sides without arbitration |
| **P2** | Answer / signaling incomplete | Need remote SDP evidence |
| **P3** | Candidate / STUN convergence | Last; insufficient evidence yet |

---

## Collect matrix (facts only)

| Fact | Answers |
|------|---------|
| offer sent? | entered execution |
| answer received? | signal closed |
| both-side restart epoch / attemptId? | collision? |
| ICE state transition after answer | transport stall |

**Out of scope / frozen**

```text
❌ enlarge ICE / attempt timeout
❌ retry policy
❌ UVCP / degraded / residency clear
❌ negotiation owner rewrite
❌ membership / completion predicate
❌ new recovery phases
```

---

## Auth gate

```text
DRAFTED (this doc)
  → product auth for R1 attribution pass (log-only on 154011 first; optional thin re-run)
  → classify A/B/C
  → only then decide next ADR knife (arbitration vs signaling vs transport)
```

**First knife after auth:** attribute **154011 existing logs** (no new flap required).  
Optional second flap only if log coverage insufficient for A/B/C.
