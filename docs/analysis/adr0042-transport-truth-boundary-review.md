# ADR-0042 — Transport Truth Boundary Review

**Status:** **REVIEW COMPLETE** · **no merge action** · **stash NOT applied**  
**Date:** 2026-08-08  
**Question:** Does ADR-0042 affect RNA intent completion semantics?  
**Baseline:** `main` @ `c592da6` · ADR-0043 [checkpoint close](./adr0043-checkpoint-close.md)  
**RNA context:** [rna-intent-lifecycle-observation-analysis.md](./rna-intent-lifecycle-observation-analysis.md)

---

## Executive verdict

```text
ADR-0042 affects transport send-fact truth only.
It does NOT own RNA intent completion semantics.

Appendix B M03 SYNC_PENDING:
  Classification B — intent lifecycle state machine
  NOT Classification A — transport fact missing
```

**Next track:** RNA hypothesis — [rna-intent-lifecycle-hypothesis.md](./rna-intent-lifecycle-hypothesis.md) (**DRAFT**)  
**Not next:** ADR-0042 merge (already on main) · Field Observation Run · ADR-0043 reopen.

---

## Branch / main state (review input)

```text
ADR-0042 P0:  MERGED on main (PR #127, 4d74f60)
Branch:        adr0042-p0-reattach-transport-truth (at merge tip 99890fd)
Stash@{0}:     doc/build only — NOT popped (see adr0042-stash-review.md)
```

This review is **boundary analysis**, not a merge request.

---

## R1 — Does ADR-0042 change truth boundary?

**Answer: Yes — tightens, does not collapse.**

Frozen layered model:

```text
QUEUED → SENDTO_SUCCESS → REMOTE_RECEIVED → REMOTE_RECEIPT_ACKED → CONTROL_ADMITTED
```

INV-T1:

```text
SENT / TRANSPORT_SENT  REQUIRES  sendto success (SIGNAL_DATAGRAM_SENT)
MUST NOT promote from WRITE_ACCEPTED or non-throwing send() alone
```

| Risk | ADR-0042 posture |
|------|------------------|
| `SENT` = delivery fact | **Rejected** — local submission only |
| `SENT` = receipt acked | **Rejected** — ADR-0035 layer |
| `SENT` = control admitted | **Rejected** — ADR-X1 layer |

P0 on main (`executeRecoveryReattachSend` + `sendReportingSubmission`) implements INV-T1.

**R1 verdict: PASS** — truth boundary is stricter, not looser.

---

## R2 — Does ADR-0042 affect intent obligation?

**Answer: Constrains misuse; does not close intent on transport success.**

### What ADR-0042 forbids

```text
transport SEND_FAILED  →  FAILED_MEDIA residency     (INV-T2 — fixed P0)
media recovered        →  control delivery proof     (INV-T4)
false SENT             →  permanent transport_in_flight block  (INV-T2/T3)
```

P0 `SEND_FAILED` reaction (`ConferenceEdgeRecoveryController`):

```text
phase → RECOVERY_PENDING
reattachDeliveryState → QUEUED
obligationOpen → true
does NOT enter FAILED_MEDIA
```

### What ADR-0042 does NOT do

```text
transport recovered  →  intent automatically terminal
SENDTO_SUCCESS     →  intent covered
REMOTE_RECEIPT     →  completion admitted
```

ADR-0042 explicitly separates:

```text
one send failure  ≠  recovery failure
media ready       ≠  control recovered
```

**R2 verdict: PASS** — ADR-0042 prevents transport/media from **incorrectly closing** intent; it does not **produce** intent terminal facts.

---

## R3 — Is ADR-0042 orthogonal to ADR-0043?

**Answer: Yes — different layer, different signal, no authority coupling.**

| | ADR-0042 | ADR-0043 |
|---|----------|----------|
| Layer | Transport send truth | Membership context proof |
| Signal | `CONFERENCE_REJOIN` reattach | `MEMBERSHIP_CONTEXT_EXISTENCE_*` + `GROUP_RESYNC` |
| Question | Was datagram actually sent? | May issuer dispatch GROUP_RESYNC? |
| Authority | Recovery obligation owner | Membership authority (O1) |

```text
ADR-0042 transport state  -X→  membership/convergence authority
ADR-0043 membership proof -X→  sendto / in-flight lifecycle
```

Appendix B episode confirms orthogonality in time:

```text
19:00:51  GROUP_RESYNC_HANDLER_ACCEPTED (membership)
19:00:56  REATTACH_INBOUND + RECEIPT_ACKED (transport)
19:00:56  DEFERRED_INTENT_UNCOVERED (intent — downstream of both)
```

**R3 verdict: PASS** — orthogonal; no architectural coupling risk identified.

---

## RNA hypothesis classification (Appendix B episode)

Given `uncoveredIntent=true` · `intentTerminal=NONE` on M02→M03:

| Class | Description | Fits Appendix B? |
|-------|-------------|----------------|
| **A** Transport fact missing | No SENT / no receipt / false in-flight | **No** — reattach delivered, ICE connected |
| **B** Intent lifecycle missing transition | Intent never terminal; obligation uncovered | **Yes** — primary |
| **C** Completion authority decision | CompletionPolicy blocks on DEFERRED_INTENT | **Secondary** — symptom of B |

```text
ADR-0042 addresses A-class failures.
Appendix B stall is B-class.
```

Historical ADR-0042 Case C (`RECOVERY_INTENT_LOST_AFTER_MEDIA_RESTORE`) is the **inverse misuse** ADR-0042 prevents — media masking missing control. Appendix B shows media+control satisfied **while intent remains uncovered** — a different question, outside ADR-0042 scope.

---

## Decision tree outcome

```text
ADR-0042 review
        |
        +--> unrelated to Appendix B SYNC_PENDING stall
        |       |
        |       v
        |   RNA directed observation (next)
        |
        +--> already merged on main (PR #127)
                |
                v
            No merge action required
            Field validation remains PAUSED per impl plan
```

---

## Explicit non-actions

```text
Do NOT pop stash@{0} onto main
Do NOT reopen ADR-0043 for intent/completion
Do NOT declare ADR-0042 field PASS from desk alone
Do NOT merge (already merged)
Do NOT write: Recovery broken · fix recovery timeout · watchdog bug
```

---

## One-line statement

> ADR-0042 tightens reattach transport truth and forbids media/transport from falsely closing control — orthogonal to ADR-0043; Appendix B `DEFERRED_INTENT_UNCOVERED` routes to RNA intent lifecycle observation, not ADR-0042.
