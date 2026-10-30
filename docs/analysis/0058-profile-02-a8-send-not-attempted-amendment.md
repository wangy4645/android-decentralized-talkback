# P02-A8 Amendment — `SEND_NOT_ATTEMPTED` (Batch Fan-out Outcome)

## Status

```text
P02-A8 Amendment:  ACCEPTED / CLOSED
Date:              2026-09-04
Parent:            P02-A8 — Per-Leg Send Failure Isolation (F1 ACCEPTED)
Trigger:           G-RES-3 E3 sendmmsg adoption review (Decisions 1–2)
Scope:             add SEND_NOT_ATTEMPTED to PerLegDeliveryOutcome only
Out of scope:      R1–R5 isolation rules; A2–A7; Profile 03 liveness policy
ADR-0060:          no change (execution-model refinement at Profile layer)
Next:              G-RES-3-C2 submission-primitive amendment → E3 H1c authorized
```

---

## Summary

E3 `sendmmsg` batch fan-out can leave a **prefix of legs submitted** and a **suffix
not reached** by the kernel in the same slot. P02-A8 frozen tri-state
(`SENT | DROPPED_LOCAL | SEND_FAILED`) does not express “submission never attempted”
without falsifying transport truth.

This amendment adds **`SEND_NOT_ATTEMPTED`** as a fourth `PerLegDeliveryOutcome`.
All existing A8 isolation rules (F1, R1–R5, anti-F2/F3) remain **unchanged**.

---

## Trigger

```text
Decision 1 (E3 adoption review):
  partial sendmmsg → PREFIX COMMIT / SUFFIX NOT-ATTEMPTED

Decision 2 (E3 adoption review):
  SEND_NOT_ATTEMPTED MUST NOT map to SEND_FAILED or DROPPED_LOCAL
  minimal enum extension required
```

---

## Frozen `PerLegDeliveryOutcome` (amended)

```text
SENT                 datagram accepted by local transport for this leg
DROPPED_LOCAL        local policy dropped before send (evidence only)
SEND_FAILED          sendto / socket error / bounded backpressure drop for this leg
SEND_NOT_ATTEMPTED   fan-out submission for this leg was not reached in the current slot
                     (e.g. batch syscall stopped at boundary; kernel did not attempt
                      this message index)
```

### `SEND_NOT_ATTEMPTED` semantics (frozen)

```text
transport evidence only
MUST NOT be counted as SEND_FAILED
MUST NOT be labeled DROPPED_LOCAL
MUST NOT roll back RTP/SRTP timeline (R1 unchanged)
MUST NOT re-protect or re-emit logical slot for other legs (R1/R5 unchanged)
MUST NOT trigger in-slot retry, second batch, or cross-slot catch-up (R3 unchanged)
MUST NOT block or cancel already-successful legs for the same slot (R2 unchanged)

Profile 03 policy on sustained SEND_NOT_ATTEMPTED → Profile 03 scope (not this amendment)
```

### Anti-patterns (rejected)

```text
SEND_NOT_ATTEMPTED → SEND_FAILED     (falsifies “not attempted”)
SEND_NOT_ATTEMPTED → DROPPED_LOCAL  (policy drop ≠ batch-boundary suffix)
partial batch → whole-slot EMIT_FAILED (F2 — still forbidden)
```

---

## E3 batch submission layer → `PerLegDeliveryOutcome` (frozen mapping)

`SendmmsgLegSubmissionOutcome` is an implementation/evidence layer for batch syscall
mapping. Product/runtime MUST map to frozen `PerLegDeliveryOutcome` as follows.

Let `sendmmsg(messages[0..8])` return `R`.

```text
R == 9:
  leg[0..8] → SENT

0 <= R < 9:
  leg[0..R-1] → SENT
  leg[R]      → SEND_FAILED
  leg[R+1..8] → SEND_NOT_ATTEMPTED

R < 0  (syscall error; zero datagrams transmitted):
  leg[0]    → SEND_FAILED
  leg[1..8] → SEND_NOT_ATTEMPTED
```

```text
failureReason on leg[R] when 0 <= R < 9:
  UNKNOWN / UNAVAILABLE (Linux partial success may lose errno)

failureReason on leg[0] when R < 0:
  errno MAY be recorded if syscall returns it; else UNKNOWN

NO in-slot retry
NO second sendmmsg for suffix legs
NO fallback DatagramSocket.send() for suffix legs
```

Sequential `send()` / per-target socket models (E0/E1) do not emit
`SEND_NOT_ATTEMPTED` under normal operation; each leg is individually attempted.

---

## Evidence counting (frozen — G-RES-3 alignment)

```text
perLegSendAttemptCount (per run):
  = count(legs with outcome SENT or SEND_FAILED)

notAttemptedCount (per run):
  = count(legs with outcome SEND_NOT_ATTEMPTED)

sendFailCount / perLegSendFailureCount:
  = count(SEND_FAILED) only
  MUST NOT include SEND_NOT_ATTEMPTED
```

Formal qualifying expectation (600s, 30,000 slots):

```text
perLegSendAttemptCount == 270,000
notAttemptedCount == 0
sendFailCount == 0
```

---

## Unchanged from P02-A8 (explicit)

```text
F1  per-leg independent delivery outcome
R1  timeline immunity
R2  cross-leg non-blocking isolation
R3  no cross-slot catch-up
R4  multicast leg independence
R5  no whole-slot retry
F2/F3  still FORBIDDEN

LogicalMediaEmissionOutcome = EMITTED independent of per-leg mix
```

---

## Normative contract insert (replaces P02-A8 outcome enum bullet)

```text
P02-A8 — PerLegDeliveryOutcome (per delivery leg, per slot):

  SENT | DROPPED_LOCAL | SEND_FAILED | SEND_NOT_ATTEMPTED

SEND_NOT_ATTEMPTED:
  submission not reached for this leg in the slot; not a send failure;
  not a local policy drop; same timeline / no-catch-up / per-leg isolation as other outcomes.

Batch fan-out (e.g. sendmmsg): map partial return per P02-A8 amendment mapping table.
```

---

## Consequences

```text
A8 adjudication doc remains authoritative for F1/R1–R5; this amendment extends enum only
A10 corpus: add vectors for partial batch (prefix SENT, boundary SEND_FAILED, suffix SEND_NOT_ATTEMPTED)
Implementation: per-leg outcome arrays in E3 harness/evidence path
ADR-0060: no amendment required at this layer (Profile 02 execution-model refinement)
```

---

## References

- `docs/analysis/0058-profile-02-a8-send-failure-isolation-adjudication.md`
- `docs/analysis/0060-gres3-e3-sendmmsg-adoption-review.md` (Decisions 1–2)
- `docs/analysis/0060-gres3-e2-sender-execution-model-adjudication.md`
