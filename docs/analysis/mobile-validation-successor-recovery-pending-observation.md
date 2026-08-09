# Successor Recovery Pending — Observation

**Status:** **OPEN OBSERVATION** · desk + Field #2 evidence · **no ADR** · **no runtime authorization**  
**Date:** 2026-08-09  
**Classification:** Recovery convergence observation  
**Not:** ADR-0045 regression · ADR-0044 presentation defect · residency clear gap

**Evidence:** `logs/adr0045-field-20260809-094259/` · session `a73272cb-74c2-4fd6-bf50-918a66806df1` · observer **M02** → peer **M03** · pid `30507`

**Parents / boundaries:**

- [ADR-0045](../adr/0045-post-obligation-failed-media-residency-clear-admission.md) — residency clear; **does not absorb this track**
- [ADR-0044](../adr/0044-user-visible-connectivity-semantics-media-residency.md) — presentation; SYNCING here follows recovering, not DEGRADED
- [ADR-0038](../adr/0038-recovery-completion-admission-contract.md) — completion success (orthogonal; do not reopen casually)
- WiFi Recovery Architecture — **CLOSED** (observation only)

---

## Status board

```text
ADR-0044 Presentation              CLOSED ✅

ADR-0045 Residency Clear           ACCEPTED
  Policy                           PASS ✅
  Phase 2 / 2.1 Field              PAUSED (no qualifying FAILED_MEDIA case on M02)

Field #1 (20260809-093047)
  M03→M02 clear                    PASS
  M02→M03 DEGRADED                 Trigger gap (FAILED_MEDIA + closed; ADR-0045)

Field #2 (20260809-094259)
  M03→M02 clear                    PASS (ADR-0045 still holds)
  M02→M03 SYNCING                  NEW OBSERVATION (this doc)
  Domain                           successor recovery convergence

Next:
  Answer Q1–Q3 below (observation / audit only)
  Do NOT force FAILED_MEDIA to validate Phase 2.1 while this is open
  Do NOT fold into ADR-0045 / ADR-0044 / Directed #5
```

---

## Observation (Field #2)

```text
mediaUnavailable=false
ice=CONNECTED
receivePathLive=true
phase=RECOVERY_PENDING
obligationOpen=true
recovering=true
finalPresence=SYNCING
```

UI 「一直 Sync」与权威一致：仍有 **open recovery obligation** → ADR-0044 投向 SYNCING，**不是** sticky DEGRADED。

### Why not ADR-0045

ADR-0045 GATE requires:

```text
phase == FAILED_MEDIA_RECOVERY
+ obligationClosed
+ snapshot E4
```

Field #2:

```text
phase = RECOVERY_PENDING
obligationOpen = true
FAILED_MEDIA_RECOVERY count (M02 pid 30507 → M03) = 0
```

```text
RecoveryResidencyClearPolicy
        ^
        |
        X   (no GATE)
```

Clear admission **not involved**. Phase 2.1 entry trigger **not exercised**.

---

## Evidence chain (M02 → M03)

```text
09:45:11  ICE_DISCONNECTED → attempt1 (owner=M03; M02 NON_OWNER_BLOCKED)
09:45:23  attempt1 → RECOVERED (obligation closed)
09:45:24  late REATTACH_INBOUND → SUPERSEDE attempt2
          ICE_RESTART_DISPATCHED · DELIVERY_CONFIRMED
          Completion WAITING (DELIVERY_PENDING → then successor)
09:45:26  ADMIT_SUCCESSOR (evidenceKind=REMOTE_MODULE_RECOVERED)
          obligationGen=2 attempt=3 → RECOVERY_PENDING
          negotiation owner=M03; DEFER_ADMISSION
…
09:47:xx  rprobe: RECOVERY_PENDING · obligationOpen=true · SYNCING
          ice=CONNECTED · receivePathLive=true · mediaUnavailable=false
09:48:06  MEMBERSHIP_LEFT / USER_LEAVE (obligation closed by leave — not completion)
```

Markers to keep:

| Marker | Role |
|--------|------|
| `RECOVERY_EDGE_RECOVERED` / `reason=RECOVERED` | attempt1 terminal |
| `RECOVERY_ATTEMPT_SUPERSEDED` / `REATTACH_INBOUND` | post-RECOVERED late reattach |
| `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` | new gen; leaves `RECOVERY_PENDING` |
| `NEGOTIATION_NON_OWNER_BLOCKED` / `DEFER_ADMISSION` | local cannot drive media action |
| rprobe `finalPresence=SYNCING` + `obligationOpen=true` | presentation follows recovering |

---

## Scope

```text
Goal:
  understand RECOVERY_PENDING lifetime after successor admission

In scope:
  phase ownership of RECOVERY_PENDING
  what closes obligationOpen after ADMIT_SUCCESSOR
  why healthy media still yields SYNCING (recovering / successor pending)

Not in scope:
  ADR-0045 residency clear / Phase 2.1 field
  ADR-0044 EndpointStatus remapping
  presentation “bugfix”
  forcing FAILED_MEDIA to mix variables
  WiFi matrix / Directed #5
  casual ADR-0038 / membership reopen
```

---

## Open questions (answer next; do not invent fixes)

### Q1 — Who owns `RECOVERY_PENDING`?

```text
Who is the phase owner after ADMIT_SUCCESSOR?
  - successor episode writer?
  - negotiation owner (M03) vs local (M02)?
  - media-action owner vs attempt clock owner?
```

Field hint: after successor, `selectedOwner=M03` while local=M02; media action assigned `HOST_RESTART` then capability `DEFER_ADMISSION`.

### Q2 — What closes `obligationOpen=true`?

```text
Not OBLIGATION_DEADLINE (that is failed-media residency path).
Candidate terminals for successor episode:
  - successor completion → RECOVERED / markRecovered
  - attempt timeout → FAILED_MEDIA_* (then ADR-0045 may apply later)
  - cancel / MEMBERSHIP_LEFT / conference leave
```

Field #2 closed only via **USER_LEAVE** (~2.5 min later) — no completion, no FAILED_MEDIA.

### Q3 — Why healthy media ≠ ONLINE?

```text
ice=CONNECTED ∧ receivePathLive=true ∧ mediaUnavailable=false
yet finalPresence=SYNCING
```

Hypothesis to verify (observation only):

```text
recovering == obligationOpen (or actively recovering phase)
→ UVCP / ADR-0044 maps to SYNCING
→ ONLINE requires recovering=false (obligation converged)
```

Confirm whether `successorPending` / membership / control reconciliation also gate completion (ADR-0038 facts) without calling that an ADR-0045 miss.

---

## Discipline

```text
PASS criteria for this observation track:
  Q1–Q3 answered with code+log provenance
  boundary held: not absorbed by ADR-0045/0044

FAIL / out of process:
  “fix Sync by clear residency”
  “Phase 2.1 field until FAILED_MEDIA”
  reopen WiFi Recovery Architecture on single sticky Sync
```

---

## References

- Field #2 logs: `talkback/logs/adr0045-field-20260809-094259/`
- Field #1 (ADR-0045 trigger gap): `talkback/logs/adr0045-field-20260809-093047/`
- ADR-0045 run card: [adr0045-field-run-card.md](./adr0045-field-run-card.md)
- Related (independent): ADR-0039 owner-conflict track — **not triggered** by this note alone
