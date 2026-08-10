# ADR-0042: Recovery Reattach Transport Delivery Semantics

## Status

**Status:** **ACCEPTED · INV-T1/T2/T4 + INV-T3 eligibility · INV-T3-SCHEDULE IMPLEMENTED** (2026-08-10)  
**Date:** 2026-08-08 (contract) · 2026-08-10 (INV-T3-SCHEDULE implementation acceptance)  
**Architect sign-off:** Transport contract ACCEPTED; schedule complement ACCEPTED + desk-verified (G4/G5).

**Parents / complements:**
- [ADR-0035](./0035-recovery-scoped-delivery-assurance.md) — Recovery-scoped delivery assurance (receipt vs never-reached)
- [ADR-0032](./0032-recovery-dispatch-eligibility-contract.md) — Dispatch eligibility planes
- [ADR-0040](./0040-obligation-convergence.md) — Obligation convergence (VERIFIED)
- [ADR-X1](./x1-control-admission-after-recovery.md) — Control admission **after** delivery (out of scope here)

**Evidence (field + desk):**

| Artifact | Role |
|----------|------|
| `logs/recovery-reattach-delivery-path-20260808-150025/` | Authoritative transport RCA (INV-T1/T2) |
| `ADJUDICATION_T2.txt` (same dir) | Case A primary · Case C secondary |
| [transport-reattach-send-semantics-followup.md](../analysis/transport-reattach-send-semantics-followup.md) | T1/T2 freeze |
| [recovery-reattach-delivery-path-investigation.md](../analysis/recovery-reattach-delivery-path-investigation.md) | Delivery path RCA |
| [adr0042-inv-t3-schedule-amendment-draft-001.md](../analysis/adr0042-inv-t3-schedule-amendment-draft-001.md) | INV-T3-SCHEDULE amendment (MERGED here) |
| [adr0042-inv-t3-diff-gate-decision-memo-001.md](../analysis/adr0042-inv-t3-diff-gate-decision-memo-001.md) | Diff gate PASS · Commits 1–4 |
| `Adr0042InvT3ScheduleProgressOracleTest` | G4 progress oracle (desk GREEN) |
| `Adr0042P0InvariantSuiteTest` | G5 regression (desk GREEN) |
| [recovery-reattach-progress-validation-001.md](../analysis/recovery-reattach-progress-validation-001.md) | Field validation run card (NEXT) |

**Runtime:** INV-T1/T2 P0 landed · INV-T3-SCHEDULE Commits 1–4 landed · **Field progress validation PASS** (2026-08-10) · Media convergence → [RMCA-001](../analysis/rmca-001-recovery-media-convergence-attribution.md) (OPEN, separate).

---

## Summary

Freeze the **transport-layer delivery semantics** for Recovery `CONFERENCE_REJOIN` / reattach control datagrams:

1. What `SENT` means (local sendto success only)
2. What `SEND_FAILED` must clear (in-flight)
3. When retry is **eligible** while an obligation remains open
4. That after `SEND_FAILED`, Recovery **MUST** establish obligation-owned **bounded progress** (INV-T3-SCHEDULE)
5. That media restore MUST NOT prove control delivery
6. Who owns transport failure vs recovery obligation vs media

**Confirmed defect (architecture wording):**

> Recovery reattach transport completion semantics mismatch: upper layer marks datagram delivery as `SENT` after write acceptance / non-throwing send API, while kernel `sendto` returns `ENETUNREACH`; failed `CONFERENCE_REJOIN` has no retry path after network restoration; false in-flight then blocks restore-time redispatch; media path can close control intent without successful control delivery; eligibility without schedule permits silent non-progress until deadline.

**Sign-off one-liner:**

> ADR-0042 fixes **send-fact truth** and **progress guarantee after SEND_FAILED**, not **delivery success** or **WiFi recovery success rate**.

**Do not write:** X1 failed · reattach delivery routing wrong · watchdog bug · Recovery broken · fix timeout

---

## Motivation

X1 assumes delivery facts exist (`REMOTE_RECEIPT_ACKED`). On the failing M03→M02 path, X1 was **never entered** because the datagram never left the sender.

```text
WiFi flap
  → WRITE_ACCEPTED
  → false RECOVERY_REATTACH_SENT (SENT)
  → sendto ENETUNREACH
  → no retry while obligation OPEN
  → network restored → DISPATCH blocked (transport_in_flight)
  → media restores → control intent closed
```

This ADR freezes the **transport contract gap** that sits **below** X1.

---

## Non-goals

- **Not** X1 admission / reevaluation / glare
- **Not** X1-B progression
- **Not** watchdog budget / timeout tuning
- **Not** presence / UVCP / UI
- **Not** X2 failed-media residency
- **Not** ADR-0040 ownership regression
- **Not** ADR-0038 completion predicate change
- **Not** membership fence / RCA-0036 reopen
- **Not** generic reliable-UDP framework for all signal types
- **Not** mandate immediate retry scheduling on every network restore (INV-T3 eligibility remains distinct from INV-T3-SCHEDULE)
- **Not** retry framework / Coordinator retry queue / ICE policy rewrite (INV-T3-SCHEDULE non-goals)

---

## 1. Problem statement

Recovery reattach currently **conflates enqueue / write acceptance with datagram transmission success**.

Observed collapse:

```text
WRITE_ACCEPTED
      ↓  (incorrectly promoted)
transportResult=SENT / TRANSPORT_SENT
      ↓
(no SIGNAL_DATAGRAM_SENT)
      ↓
SEND_FAILED ENETUNREACH
```

Secondary invariant violation (not primary failure mode):

```text
failed packet + uncleared in-flight
      ↓
restore-time DISPATCH_REATTACH rejected (transport_in_flight)
```

Masking (not repair):

```text
MEDIA_READY / REATTACH_MEDIA_ALREADY_LIVE
      ≠
CONTROL_RECOVERED
```

---

## 2. Classification freeze (T2)

| Class | Label | Role |
|-------|-------|------|
| **Primary** | Case A `TRANSPORT_RETRY_MISSING` | `SEND_FAILED` while `obligationOpen` · no rejoin retry |
| **Secondary** | Case C `RECOVERY_INTENT_LOST_AFTER_MEDIA_RESTORE` | Media path closes control intent without successful rejoin |
| **Contributing** | `SENT_SEMANTICS_MISMATCH` · false `transport_in_flight` | Secondary invariant violation |
| **Out** | Case B attempt closed at send fail | Ruled out (`ATTEMPT_DISPATCHING` · `obligationOpen=true`) |
| **Out** | X1 / X1-B / X2 | Not entered / HOLD |

---

## 3. Transport facts — layered model (ACCEPTED)

```text
QUEUED
   |
   v
SENDTO_SUCCESS          (= local SENT / SIGNAL_DATAGRAM_SENT)
   |
   v
REMOTE_RECEIVED
   |
   v
REMOTE_RECEIPT_ACKED    (ADR-0035)
   |
   v
CONTROL_ADMITTED        (ADR-X1)
```

Optional observe rung (not a delivery claim): `WRITTEN` / `WRITE_ACCEPTED` may exist between `QUEUED` and `SENDTO_SUCCESS`.

**Frozen discipline (must not collapse):**

```text
QUEUED              ≠ SENT
SENDTO_SUCCESS      ≠ REMOTE_RECEIVED
REMOTE_RECEIVED     ≠ RECEIPT_ACKED
RECEIPT_ACKED       ≠ CONTROL_ADMITTED
MEDIA_READY         ≠ CONTROL_RECOVERED
```

Layer ownership of the stack:

```text
ADR-0042   Transport Delivery Truth (this ADR)
        ↓
ADR-0035   Receipt Contract
        ↓
ADR-X1     Control Admission Contract
        ↓
           Recovery Completion
```

Legacy log token `transportResult=SENT` MUST mean **`SENDTO_SUCCESS` only** (INV-T1). Until impl, treat current `SENT` as **untrusted**.

---

## 4. Required invariants (ACCEPTED)

### INV-T1 — SENT = local datagram submission success only

```text
transportResult=SENT
  OR deliveryState=TRANSPORT_SENT
REQUIRES
  sendto success (SIGNAL_DATAGRAM_SENT for that nonce/type)
```

**Wording (frozen):**

> `SENT` represents successful **local datagram submission** only.

MUST NOT interpret `SENT` / `SENDTO_SUCCESS` as:

- remote received
- receipt acked
- control admitted

MUST NOT mark `SENT` solely because:

- `WRITE_ACCEPTED`
- `signalingChannel.send()` returned without throwing
- enqueue / API accept

### INV-T2 — SEND_FAILED clears in-flight; instance ≠ obligation

```text
SEND_FAILED
MUST clear any in-flight / transport-in-flight ownership
for that reattach transmission instance
```

**Forbidden:**

```text
failed packet + inFlight=true
```

**Wording (frozen):**

> `SEND_FAILED` is **terminal for this transmission instance**, but **not** terminal for the recovery obligation.

```text
one send failure  ≠  recovery failure
```

`transport_in_flight` after `SEND_FAILED` is a **secondary invariant violation**, not the primary failure mode.

### INV-T3 — Retry eligibility (not mandatory schedule)

```text
obligationOpen=true
AND transport failure recovered (link / route usable again)
⇒
reattach control dispatch is retry-eligible
```

**Wording (frozen):**

```text
eligible ≠ scheduled
```

This ADR freezes **eligibility** for INV-T3. Immediate retry, specific backoff, and max-attempt counts remain non-goals.

While the recovery episode still requires control delivery, a prior `SEND_FAILED` MUST NOT permanently consume the only dispatch slot via false in-flight.

### INV-T3-SCHEDULE — Bounded progress after transport send failure (IMPLEMENTED)

**Amendment origin:** [adr0042-inv-t3-schedule-amendment-draft-001.md](../analysis/adr0042-inv-t3-schedule-amendment-draft-001.md) (MERGED 2026-08-10).

**Applies when:**

```text
For a recovery reattach obligation:
Recovery owner observes SEND_FAILED on an outbound reattach dispatch attempt
(initiatesReattach).
```

**Normative (ACCEPTED):**

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
PROGRESS_WINDOW_EXPIRED          ≠  DELIVERY_FAILED
WAKEUP_ARMED (capability defer)  MAY coexist with PROGRESS_WINDOW_ARMED
```

**Ownership (frozen):**

| Concern | Owner |
|---------|-------|
| Progress window arm / fire / satisfy / expire | `ConferenceEdgeRecoveryController` |
| UDP send execution | `TalkbackCoordinator` via `onRequestReattach` (executor only) |
| Coordinator retry queue / schedule policy | **Forbidden** |

**Lifecycle facts (implemented):**

```text
RECOVERY_PROGRESS_WINDOW_ARMED
RECOVERY_PROGRESS_WINDOW_FIRED
RECOVERY_PROGRESS_WINDOW_REEVALUATE
RECOVERY_PROGRESS_WINDOW_SATISFIED
RECOVERY_PROGRESS_WINDOW_EXPIRED
```

**Trigger model:**

```text
External event (ROUTE_CONVERGED / DIGEST_REFRESH / …):  MAY accelerate
Recovery progress schedule:                            MUST guarantee opportunity path
```

`DIGEST_REFRESH` / `ROUTE_CONVERGED` / `ICE_CHECKING` MUST NOT be the **sole** redispatch path after `SEND_FAILED`.

**Non-goals (INV-T3-SCHEDULE):**

```text
rollbackNegotiation / ADR-0049 reuse
ICE restart algorithm rewrite
fan-out suppression / session isolation
membership retry ownership
completion predicate change (ADR-0038)
watchdog / obligation deadline budget change
Coordinator-owned retry queue
RetryManager / global RecoveryScheduler
```

**Implementation evidence (desk):**

| Commit | Content |
|--------|---------|
| 1 | `ProgressWindowState` + `EdgeRecoveryRecord` fields |
| 2 | Arm progress window on `SEND_FAILED` |
| 3 | `FIRED` → `runCompletionEvaluationStub` / `onRequestReattach` |
| 4 | G4 progress oracle + G5 Adr0042 suite |

**Field effect:** NOT YET VALIDATED — [recovery-reattach-progress-validation-001.md](../analysis/recovery-reattach-progress-validation-001.md).

### INV-T4 — Media ≠ control delivery

```text
MEDIA_READY / ICE_CONNECTED / REATTACH_MEDIA_ALREADY_LIVE
!=
CONTROL_RECOVERED / control delivery complete
```

Media restore MAY proceed on a separate plane. It MUST NOT silently prove that `CONFERENCE_REJOIN` was delivered or that control admission inputs exist.

Boundary with ADR-0040: media evidence may advance **L2** facts; it MUST NOT invent **control delivery** facts required for L3/X1.

---

## 5. Transport failure state ownership (ACCEPTED)

| Layer | Owns | Must not own |
|-------|------|----------------|
| **Transport** | send-result truth · in-flight lifecycle | recovery deadline · obligation close |
| **Recovery** | retry obligation · deadline · whether episode remains open · **progress window schedule (INV-T3-SCHEDULE)** | inventing `SENT` without sendto success |
| **Media** | media availability only | closing recovery because media is live |

**Forbidden cross-layer close:**

```text
transport failed
       ↓
media recovered
       ↓
someone closes recovery   ← prohibited as proof of control delivery
```

---

## 6. Explicit non-claims

This ADR does **not** claim:

- WiFi flap itself is a product defect
- Receiver filter dropped the packet (field: packet never emitted)
- Wrong destination address (field: dst matched M02)
- X1 admission graph is broken on this fail edge (never entered)
- Immediate retry must fire on every network restore

---

## 7. Relation to ADR-0035 / ADR-X1

ADR-0035: Recovery MUST distinguish **never reached peer** vs **reached but negotiation incomplete**.

ADR-0042: Freezes **sender-side transport truth** below ADR-0035.

ADR-X1: Admission **after** `REMOTE_RECEIPT_ACKED` — not entered until ADR-0042 + ADR-0035 facts exist.

Three layers MUST NOT be merged.

---

## 8. Implementation posture (post INV-T3-SCHEDULE)

```text
INV-T1 / INV-T2                  P0 LANDED · field PASS (narrow)
INV-T3 eligibility               ACCEPTED (unchanged)
INV-T3-SCHEDULE                  IMPLEMENTED · desk G4/G5 GREEN · field progress PASS
Media convergence after SENT     OPEN — RMCA-001 (independent)
ADR-0049 rollback                FROZEN · independent
X1 / X2                          DO NOT REOPEN from this ADR
```

**Allowed next:** RMCA-001 attribution (no INV-T3 reopen).

**Not allowed as side-effect of this ADR:** RFA soak reopen · ADR-0049 · completion predicate · ICE policy rewrite · INV-T3 rollback.

---

## 9. Decision

**ACCEPTED** as the transport + progress-schedule contract for Recovery reattach (INV-T1..T4 + INV-T3-SCHEDULE + failure-state ownership).

**Field effect of INV-T3-SCHEDULE:** pending dedicated progress validation (not WiFi success-rate soak).

---

## 10. One-line gate

> ADR-0042: send-fact truth + obligation-owned bounded progress after `SEND_FAILED` — not delivery success, not WiFi recovery SLA.
