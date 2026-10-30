# E16 Edge Admission Gap Analysis

**Date:** 2026-08-04  
**Evidence:** `logs/joint-d1-c-e16-clean-20260804-161614`  
**Parent:** [e16-clean-attempt-edge-trigger-analysis.md](./e16-clean-attempt-edge-trigger-analysis.md)  
**Code:** `ConferenceEdgeRecoveryController.admitSuccessorObligationEpisode` / `beginRecovery` / `admitSupersededRecoveryAttempt`  
**Lifecycle contract:** [e16-edge-lifecycle-contract-v1.md](./e16-edge-lifecycle-contract-v1.md)

**Verdict (unchanged):**

```text
FAIL_CLEAN_JOINT
reason   = J_E_D1_NOT_ESTABLISHED
subtype  = EDGE_STARTED_NOT_EMITTED   # M02→M03
```

**Non-goals / do not modify:** CompletionPolicy · D1 retry · DeferredIntentAuthority · R4 · production patches · field re-run · J-X reopen

---

## Observed (frozen)

```text
NETWORK_LOST accepted          (M03 @ 16:18:31)
ADMIT_SUCCESSOR_OBLIGATION…    (M02→M03 @ 16:19:08)
RECOVERY_EDGE_STARTED(M02→M03) = missing
RECOVERY_EDGE_STARTED(M03→M01) = present (ICE_DISCONNECTED + later SUPERSEDE)
```

Authority attempt open on M02→M03 after flap:

```text
pathway=NEW_OBLIGATION_EPISODE
reason=ADMIT_SUCCESSOR_OBLIGATION_EPISODE
evidenceKind=REMOTE_MODULE_RECOVERED
previousPhase=CONNECTED
previousObligationOpen=true
obligationGen=2 attempt=4
```

---

## Q1 — Who owns ADMIT_SUCCESSOR? Does it emit EDGE?

### Owner

`ConferenceEdgeRecoveryController` (session recovery authority), **not** DeferredIntentAuthority / CompletionPolicy / D1.

Call path (field):

```text
REMOTE_MODULE_RECOVERED evidence
  → successor admission predicate
  → admitSuccessorObligationEpisode(...)
```

### Contract in code (ADR-0022 §13.2.4 comment)

```text
admission ≠ beginRecovery fusion
resolve/dispatch separately (M1, INV-REC-023)
```

`admitSuccessorObligationEpisode` does:

1. expire deferred ICE intent audit (`ADMIT_SUCCESSOR`)
2. `openNewRecoveryObligation` → `RECOVERY_PENDING` / `ATTEMPT_REQUESTED`
3. log `ADMIT_SUCCESSOR_OBLIGATION_EPISODE`
4. `resolveMediaActionOwner` / reattach dispatch

It does **not** call `beginRecovery`, and does **not** log `RECOVERY_EDGE_STARTED`.

By contrast:

| Path | Emits `RECOVERY_EDGE_STARTED`? |
|------|--------------------------------|
| `beginRecovery` (ICE/UPSERT) | **Yes** |
| `admitSupersededRecoveryAttempt` (`pathway=SUPERSEDE`) | **Yes** (`pathway=SUPERSEDE`) |
| `admitSuccessorObligationEpisode` (`NEW_OBLIGATION_EPISODE`) | **No** (by current fusion split) |

### Semantic boundary (frozen — not A/B)

```text
ADMIT_SUCCESSOR today = Admission (+ may touch Delivery resolve)
                     ≠ Activation (EDGE_START_EVIDENCE)
```

**Proven by evidence:** `ADMIT_SUCCESSOR` and `RECOVERY_EDGE_STARTED` are not the same event.

**Rejected:** accepting `ADMIT_SUCCESSOR` as Joint edge-start substitute  
(would arm D1/completion clocks without proving edge/transport lifecycle).

**Not frozen:** whether Activation must be `RECOVERY_EDGE_STARTED` after admission (Option A)
or a distinct successor activation event (Option B).

Higher rule (see [e16-edge-lifecycle-contract-v1.md](./e16-edge-lifecycle-contract-v1.md)):

```text
Joint requires authority-owned Edge Lifecycle Start / Activation evidence.
ADMIT_SUCCESSOR alone is insufficient.
```

---

## Q2 — Why M03→M01 has EDGE but M02→M03 does not?

| Item | M02→M03 (missing EDGE) | M03→M01 (EDGE present) |
|------|-------------------------|-------------------------|
| Local module | M02 (authority host) | M03 (flapped peer) |
| Remote | M03 | M01 |
| Trigger into recovery | `REMOTE_MODULE_RECOVERED` → **ADMIT_SUCCESSOR** | `ICE_DISCONNECTED` → **UPSERT/beginRecovery**; later `REMOTE_MODULE_RECOVERED` → **SUPERSEDE** |
| Pathway | `NEW_OBLIGATION_EPISODE` | `UPSERT_EDGE` then `SUPERSEDE` |
| `RECOVERY_EDGE_STARTED` | **absent** | **present** (attempt 5 ICE; attempt 6 SUPERSEDE) |
| Prior local state | attempt3 `CONNECTED`, obligation still open (debug/C EXECUTED episode) | fresh ICE loss / failed-media residency then supersede |
| Obligation / intent | post-`EXECUTED(R4)` on authority edge | no Shape-A C EXECUTED on that edge |

**Not a global “EDGE never works” bug.**  
EDGE emission is **pathway-selected**: ICE/UPSERT and SUPERSEDE emit; **successor obligation admission does not**.

Likely class: **authority-edge selection / pathway asymmetry**, not universal edge emitter failure.

---

## Q3 — Is successor path correct after EXECUTED?

Post-`EXECUTED(R4)` this is **post-execution recovery lifecycle**, not deferred-negotiation ownership.

Do **not** reopen DeferredIntentAuthority / J-X for this gap.

What the archive shows:

1. Shape A completed negotiation execution on M02→M03 (attempt 3 / R4).
2. Pre-flap L2 delivery already exercised from that execution.
3. After M03 WiFi loss/recover, M02 admitted **successor obligation gen=2** via `REMOTE_MODULE_RECOVERED` while prior phase was still `CONNECTED` + obligation open.
4. That admission path never produced the EDGE token Joint uses as D1 episode gate for `M02→M03`.

Phase-3 question (refined — substitute path closed):

```text
Session/Conference Recovery Authority:
  after REMOTE_MODULE_RECOVERED on an authority edge that already EXECUTED,
  which transition is Activation (edge exists + recovery convergence allowed)?

Not: treat ADMIT_SUCCESSOR as Activation.
Open: emit RECOVERY_EDGE_STARTED after Admission (A)
   vs distinct successor activation evidence (B).
```

---

## Evidence tree (unchanged top-level)

```text
FAIL_CLEAN_JOINT
  reason = J_E_D1_NOT_ESTABLISHED
    subtype = EDGE_STARTED_NOT_EMITTED   ← current (M02→M03)
      mechanism = ADMIT_SUCCESSOR path without beginRecovery/SUPERSEDE EDGE log
```

Ruled out for this sample: ownership wipe · D1 entered-then-failed as primary · CompletionPolicy premature RECOVERED · live-only observer miss of an archived M02→M03 EDGE.

---

## Next (still analysis-only)

1. **Done:** Edge Lifecycle Contract v1 — Admission ≠ Activation ≠ Delivery ≠ Completion  
   ([e16-edge-lifecycle-contract-v1.md](./e16-edge-lifecycle-contract-v1.md)).  
2. **Now:** ActivationEvidence options — [e16-successor-activation-evidence-options.md](./e16-successor-activation-evidence-options.md)  
   (lean B / `SUCCESSOR_ACTIVATED`; **not frozen**).  
3. No field / no code until Activation has a concrete authority-owned event + producer.

```text
Phase 3 current pin:
  Admission present (ADMIT_SUCCESSOR)
  Activation missing (no EDGE_START_EVIDENCE on M02→M03)
  not D1, not J-X, not CompletionPolicy
```
