# ADR-0042 INV-T3-SCHEDULE Amendment

**ID:** adr0042-inv-t3-schedule-amendment-draft-001  
**Date:** 2026-08-10  
**Type:** AMENDMENT · **MERGED INTO ADR-0042 §4** (2026-08-10)  
**Amends:** [ADR-0042 Recovery Reattach Transport Delivery Semantics](../adr/0042-recovery-reattach-transport-delivery-semantics.md) — **INV-T3-SCHEDULE**  
**Predecessors:** Step 3B Liveness Schedule Design · Step 3A Contract Gap · Step 2 Investigation · RFA-001 field evidence  
**Acceptance review:** `adr0042-inv-t3-schedule-acceptance-review-001.md`

**Status:** **MERGED** — normative text lives in ADR-0042 §4 (INV-T3-SCHEDULE).  
**Implementation:** Commits 1–4 COMPLETE · desk G4/G5 GREEN.  
**Field:** [recovery-reattach-progress-validation-001.md](./recovery-reattach-progress-validation-001.md) — DRAFT · NOT AUTHORIZED.

---

## Status board

```text
ADR-0042 INV-T1/T2          ACCEPTED · P0 LANDED
ADR-0042 INV-T3 eligibility ACCEPTED (unchanged)
ADR-0042 INV-T3-SCHEDULE    MERGED · IMPLEMENTED · field progress PASS

Commits 1–4                 COMPLETE
Desk G4/G5                  GREEN
Field progress validation   PASS (reattach liveness CLOSED)
Media convergence           OPEN — rmca-001 (separate)

ADR-0049 rollback           FROZEN · EXCLUDED
WiFi soak / RLA / RFA       CLOSED
```

---

## WiFi Recovery Investigation — final positioning

```text
Trigger:
    WiFi interruption

Amplifier:
    fan-out recovery concurrency

Failure mechanism:
    SEND_FAILED after reattach dispatch

Missing guarantee:
    obligation-owned bounded progress

Candidate fix:
    INV-T3-SCHEDULE
```

**Excluded (closed):**

```text
WiFi instability (root)
ICE bug (root)
rollback missing (ADR-0049)
```

**User-visible failure (reframed):**

> WiFi is a **trigger**. The UX failure is recovery entering a **non-progressing WAITING state** after transport `SEND_FAILED`, with no obligation-owned redispatch before terminal disposition.

---

## §1 — Existing contract (unchanged)

### INV-T3 — Retry eligibility (ADR-0042 §4, frozen)

```text
obligationOpen = true
AND transport failure recovered (link / route usable again)
⇒
reattach control dispatch is retry-eligible

eligible ≠ scheduled
```

### Companion invariants (unchanged)

| Invariant | Meaning |
|-----------|---------|
| **INV-T1** | `SENT` = local `sendto` success only |
| **INV-T2** | `SEND_FAILED` clears in-flight for **this transmission instance**; **not** obligation terminal |
| **INV-T4** | Media restore ≠ control delivery |

### Ownership (ADR-0042 §5, unchanged)

| Layer | Owns |
|-------|------|
| **Recovery** | retry obligation · deadline · episode open/close |
| **Transport** | send-result truth · in-flight instance lifecycle |
| **Media** | media availability only |

**This amendment does not modify INV-T1, INV-T2, INV-T4, or eligibility wording of INV-T3.**

---

## §2 — Gap (why schedule amendment is required)

### 2.1 `eligible ≠ scheduled` in practice

```text
SEND_FAILED
    ↓
WAKEUP_ARMED (ROUTE_CONVERGED binding)
    ↓
wait for external reevaluate only
    ↓
        ├─ ROUTE_CONVERGED / DIGEST_REFRESH → redispatch (EP05)
        └─ no qualifying external event → ICE_FAILED → DEADLINE (EP04)
```

### 2.2 Field proof (RFA-001)

| Episode | After `SEND_FAILED` | External retry trigger | Terminal |
|---------|---------------------|------------------------|----------|
| EP04 | `WAKEUP_ARMED` | **Absent** | `OBLIGATION_DEADLINE` |
| EP05 | `WAKEUP_ARMED` | `DIGEST_REFRESH` (membership side-effect) | `EDGE_RECOVERED` |

**Conclusion:** Eligibility without schedule permits **silent non-progress** until deadline. EP04 proves: **absence of external events ≠ recovery should wait indefinitely.**

---

## §3 — ACCEPTED amendment: INV-T3-SCHEDULE

### 3.1 Placement

Amend ADR-0042 §4 **after** INV-T3 eligibility block. INV-T3 eligibility text **remains verbatim**. INV-T3-SCHEDULE adds the **schedule** complement.

### 3.2 Normative text (ACCEPTED)

#### INV-T3-SCHEDULE — Bounded progress after transport send failure

**Applies when:**

```text
For a recovery reattach obligation:
Recovery owner observes SEND_FAILED on an outbound reattach dispatch attempt.
```

**Normative:**

```text
When the Recovery owner observes SEND_FAILED,
it MUST establish a bounded progress window
owned by the recovery episode.

Within this progress window:

- Recovery MAY wait for capability restoration.
- Recovery MAY be accelerated by external events.
- Recovery MUST retain an obligation-owned path
  to attempt redispatch before terminal disposition.

Failure to deliver successfully does not violate this invariant.

Failure to establish bounded progress does violate this invariant.
```

**Keyword freeze:**

```text
MUST establish bounded progress  ≠  MUST deliver successfully
```

### 3.3 Frozen boundaries (Architect Acceptance)

#### Boundary 1 — Retry owner

**Accept:**

```text
Recovery Controller (ConferenceEdgeRecoveryController)
    owns:
        progress window
        retry eligibility (schedule complement)
        terminal disposition
```

**Reject:**

```text
Coordinator owns retry queue / schedule policy
```

Coordinator is transport **executor** only; not recovery lifecycle owner.

#### Boundary 2 — Trigger model

**Accept:**

```text
External event:     MAY accelerate progress
Recovery schedule:  MUST guarantee progress path exists
```

**Reject as sole retry trigger:**

```text
DIGEST_REFRESH
ROUTE_CONVERGED
ICE_CHECKING
```

These MAY accelerate; they MUST NOT be the **only** path to redispatch after `SEND_FAILED`.

#### Boundary 3 — Failure classification

| Term | Meaning | Use |
|------|---------|-----|
| **`PROGRESS_WINDOW_EXPIRED`** | Recovery owner established bounded progress; obligation did not reach successful delivery before window/terminal policy | Schedule liveness outcome |
| **`DELIVERY_FAILED`** | Transport send / reachability failure for a specific dispatch attempt | Transport domain (INV-T1/T2) |

**Do not use:** `retry failed` — conflates progress with delivery success.

Schedule violation (no window established) is distinct from `PROGRESS_WINDOW_EXPIRED` (window ran; delivery not achieved).

### 3.4 Relationship to INV-T3 eligibility

```text
INV-T3 (eligibility):  route restored ⇒ MAY dispatch (unchanged)
INV-T3-SCHEDULE:       SEND_FAILED    ⇒ MUST establish bounded progress window
```

Both may be true. Schedule does not remove action-gate deferral when capability is genuinely unavailable (INV-REC-001).

### 3.5 Progress contract vs `obligationOpen`

**Before amendment:**

```text
obligationOpen  ⇒  episode not closed
```

**After amendment (when schedule applies):**

```text
obligationOpen  +  outbound reattach SEND_FAILED pending
    ⇒  episode not closed
    AND  bounded progress contract active until satisfied or explicit terminal
```

---

## §4 — Ownership (frozen)

| Concern | Owner |
|---------|-------|
| Progress window arm / satisfy / terminal | **ConferenceEdgeRecoveryController** |
| UDP send execution | **TalkbackCoordinator** (executor) |
| Reachability / digest facts | **TalkbackCoordinator** → Recovery consumes |
| Retry policy queue on Coordinator | **Forbidden** |
| Membership as retry owner | **Forbidden** |

---

## §5 — Explicit non-goals (frozen)

```text
Not included:

  rollbackNegotiation() / ADR-0049 rollback reuse
  ICE restart algorithm rewrite
  fan-out suppression / session isolation gate
  membership retry ownership
  DIGEST_REFRESH as primary retry schedule
  completion predicate change (ADR-0038)
  watchdog / obligation deadline budget change
  WiFi driver / link-layer work
  generic reliable-UDP for all signal types
  Coordinator-owned retry queue
```

---

## §6 — Step 4 implementation boundary (ceiling)

**Not production authorization.** Defines seam classes a Step 4 submission may propose.

### Allowed

```text
ConferenceEdgeRecoveryController
  + progress / retry scheduling state (obligation-scoped)
  + lifecycle facts (incl. PROGRESS_WINDOW_* observability)
  + dispatch via existing onRequestReattach

TalkbackCoordinator
  + consume-only: no new retry policy
```

### Forbidden

```text
TalkbackCoordinator retry queue / schedule policy
ICE restart behavior rewrite
Membership / digest-as-schedule dependency
ADR-0049 rollback reuse
Completion / RNA-5/6 / UI / banner changes
```

**Next artifact (submitted):**

```text
adr0042-inv-t3-implementation-submission-001.md
```

**IA gate:** PENDING — no production code until ACCEPT.

---

## §7 — Evidence chain

| Stage | Artifact | Finding |
|-------|----------|---------|
| RLA / RFA | Field + seam | WiFi trigger; fan-out amplifier |
| A1 / B1 / C1 | EP04/EP05 | Retry path diverges |
| Step 2 | Code + field | No progress owner |
| Step 3A | Contract | `eligible ≠ scheduled` |
| Step 3B | Design | Window-first; Controller owns |
| Step 3C | This doc + acceptance review | Contract frozen |

---

## §8 — Acceptance gate record

| Gate | Focus | Result |
|------|-------|--------|
| **G1** | Ownership — Recovery Controller owns progress; Coordinator executor only | **PASS** |
| **G2** | Thread — no ADR-0049 / completion / membership / ICE scope creep | **PASS** |
| **G3** | Lifecycle facts — auditable window arm / progress path / terminal | **PASS** |
| **G4** | Progress oracle criteria — liveness not success rate | **CRITERIA FROZEN** · oracle draft **DEFERRED** to Step 4 |
| **G5** | Regression boundary — non-goals §5 frozen | **PASS** |

**G4 note:** Test oracle must **obey** contract; not shape it. When drafted in Step 4, pass condition is:

```text
SEND_FAILED → progress window created → retry opportunity exists → terminal disposition explicit
```

**Not:** WiFi flap recovery success rate.

**G5 merge:** Amendment accepted as **sibling doc** pending ADR-0042 §4 merge at implementation submission.

---

## §9 — Decision record

| Item | Status |
|------|--------|
| INV-T3-SCHEDULE normative text | **ACCEPTED** (§3.2) |
| Three boundaries (owner / trigger / classification) | **FROZEN** |
| Non-goals | **FROZEN** |
| Step 4 submission doc | **AUTHORIZED TO PREPARE** |
| Progress test oracle (G4 draft) | **DEFERRED** to Step 4 |
| ADR-0042 §4 merge | **PENDING** (at submission) |
| Production code | **NOT AUTHORIZED** |

---

## §10 — References

| Doc | Role |
|-----|------|
| [ADR-0042](../adr/0042-recovery-reattach-transport-delivery-semantics.md) | Parent · INV-T1..T4 |
| [ADR-0022](../adr/0022-recovery-completion-ownership.md) | INV-REC-001 |
| [ADR-0032](../adr/0032-recovery-dispatch-eligibility-contract.md) | Action gate |
| `adr0042-inv-t3-schedule-acceptance-review-001.md` | Architect acceptance record |
| `recovery-reattach-liveness-schedule-design-001.md` | Step 3B |
| `logs/recovery-layer-attribution-rfa-001-20260810-111902/` | EP04/EP05 |

---

## §11 — One-line gate

> INV-T3-SCHEDULE: after `SEND_FAILED`, Recovery **must own bounded progress** — not delivery success — with an obligation-owned redispatch path before terminal disposition.
