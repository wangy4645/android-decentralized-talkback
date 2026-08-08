# ADR-0042: Recovery Reattach Transport Delivery Semantics

## Status

**Status:** **ACCEPTED FOR IMPLEMENTATION REVIEW** (2026-08-08)  
**Meaning:** Contract approved. **Does not authorize runtime / code changes.**  
**Date:** 2026-08-08  
**Architect sign-off:** ACCEPTED — enter implementation **design** review only.

**Parents / complements:**
- [ADR-0035](./0035-recovery-scoped-delivery-assurance.md) — Recovery-scoped delivery assurance (receipt vs never-reached)
- [ADR-0032](./0032-recovery-dispatch-eligibility-contract.md) — Dispatch eligibility planes
- [ADR-0040](./0040-obligation-convergence.md) — Obligation convergence (VERIFIED)
- [ADR-X1](./x1-control-admission-after-recovery.md) — Control admission **after** delivery (out of scope here)

**Evidence (field + desk):**

| Artifact | Role |
|----------|------|
| `logs/recovery-reattach-delivery-path-20260808-150025/` | Authoritative transport RCA |
| `ADJUDICATION_T2.txt` (same dir) | Case A primary · Case C secondary |
| [transport-reattach-send-semantics-followup.md](../analysis/transport-reattach-send-semantics-followup.md) | T1/T2 freeze |
| [recovery-reattach-delivery-path-investigation.md](../analysis/recovery-reattach-delivery-path-investigation.md) | Delivery path RCA |

**Runtime / field:** FROZEN · Field validation PAUSED · No impl until separate implementation plan review ACCEPT.

---

## Summary

Freeze the **transport-layer delivery semantics** for Recovery `CONFERENCE_REJOIN` / reattach control datagrams:

1. What `SENT` means (local sendto success only)
2. What `SEND_FAILED` must clear (in-flight)
3. When retry is **eligible** while an obligation remains open
4. That media restore MUST NOT prove control delivery
5. Who owns transport failure vs recovery obligation vs media

**Confirmed defect (architecture wording):**

> Recovery reattach transport completion semantics mismatch: upper layer marks datagram delivery as `SENT` after write acceptance / non-throwing send API, while kernel `sendto` returns `ENETUNREACH`; failed `CONFERENCE_REJOIN` has no retry path after network restoration; false in-flight then blocks restore-time redispatch; media path can close control intent without successful control delivery.

**Sign-off one-liner:**

> ADR-0042 fixes **send-fact truth**, not **recovery success**. Evidence is sufficient; contract direction is correct; approved for implementation **design** review only.

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
- **Not** runtime patch / retry implementation / transport refactor in this acceptance
- **Not** mandate immediate retry scheduling (see INV-T3)

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

This ADR freezes **eligibility** only. It does **not** require immediate retry, specific backoff, or max-attempt counts (impl / plan review).

While the recovery episode still requires control delivery, a prior `SEND_FAILED` MUST NOT permanently consume the only dispatch slot via false in-flight.

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
| **Recovery** | retry obligation · deadline · whether episode remains open | inventing `SENT` without sendto success |
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

## 8. Implementation posture (post-accept)

```text
Contract                ACCEPTED FOR IMPLEMENTATION REVIEW
Runtime change          FROZEN
Field validation        PAUSED
Implementation plan     NEXT (separate review)
Code / retry / refactor NOT authorized yet
X1 / X2                 DO NOT REOPEN from this ADR
```

**Allowed next:** ADR-0042 final text freeze (this doc) · implementation **plan** review.

**Not allowed yet:** runtime patch · retry implementation · transport refactor.

Suggested impl themes for **plan** review only (non-binding):

1. Propagate `SEND_FAILED` to recovery (do not log `TRANSPORT_SENT`)
2. Clear in-flight on failure (INV-T2)
3. Mark rejoin redispatch **eligible** when INV-T3 holds (schedule is plan detail)
4. Keep media restore from inventing delivery/receipt facts (INV-T4)

---

## 9. Decision

**ACCEPTED FOR IMPLEMENTATION REVIEW** as the transport contract for Recovery reattach send semantics (INV-T1..T4 + failure-state ownership).

**Not accepted for:** code merge authorization. Separate implementation plan review required.

---

## 10. One-line gate

> ADR-0042 fixes send-fact truth, not recovery success. X1 was never entered on the fail path; false `SENT` after `ENETUNREACH`, missing retry eligibility, false in-flight, and media masking closed the control path.
