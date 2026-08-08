# Transport Reattach Send Semantics Follow-up

**Status:** T1/T2 PASS · **ADR-0042 ACCEPTED FOR IMPLEMENTATION REVIEW** (contract only; runtime FROZEN)  
**Case:** `transport-reattach-send-semantics`  
**Date:** 2026-08-08  
**ADR:** [0042-recovery-reattach-transport-delivery-semantics.md](../adr/0042-recovery-reattach-transport-delivery-semantics.md)  
**Parent:** [Delivery path investigation](./recovery-reattach-delivery-path-investigation.md)  
**Evidence (field):** `talkback/logs/recovery-reattach-delivery-path-20260808-150025`  
  session `2e73a33e-8705-4704-9028-2efab87669a9` · nonce `0d32c7a2-7dec-4e63-84da-50f0ef4e483f`  
  T2: `ADJUDICATION_T2.txt` in same log dir

**Next:** [adr0042-implementation-plan.md](./adr0042-implementation-plan.md) DRAFT FOR REVIEW · Plan APPROVED → then impl branch  
**Runtime:** FROZEN · Field validation PAUSED

---

## Frozen root cause (do not dilute)

**EN:** Recovery reattach transport completion semantics mismatch: upper layer marks datagram delivery as `SENT` after write acceptance / non-throwing send API, while kernel `sendto` returns `ENETUNREACH`; failed `CONFERENCE_REJOIN` has no retry path after network restoration.

**ZH:** 恢复重连消息的发送成功语义过早：上层把 write accepted / 无异常返回当成 `SENT`，但实际 `sendto` 因网络不可达失败；网络恢复后没有重新发送 rejoin，因此 media 恢复但 control obligation 未闭合。

```text
WRITE_ACCEPTED                 PASS
sendto ENETUNREACH             FAIL
datagram emitted               NO
receiver inbound               NO
reattach retry after restore   MISSING
```

**Failure layer:** Transport send semantics (not X1, not routing target, not admission).

---

## Status board

```text
ADR-0040                 VERIFIED PASS

X1-A                     VERIFIED
X1-B                     NOT ENTERED

Delivery Path            ROOT CAUSE FOUND
Failure layer            Transport send semantics

Evidence:
  WRITE_ACCEPTED          PASS
  sendto ENETUNREACH      FAIL
  datagram emitted        NO
  receiver inbound        NO
  Recovery retry          MISSING

T2 PRIMARY               Case A TRANSPORT_RETRY_MISSING
T2 compound              transport_in_flight @ ROUTE_CONVERGED (false SENT)
T2 SECONDARY             Case C media restore closed obligation
T2 NOT                   Case B

Transport ADR            ADR-0042 ACCEPTED FOR IMPLEMENTATION REVIEW
Field validation         PAUSED
Implementation plan      NEXT (separate review)
Runtime change           FROZEN
X1                       DO NOT REOPEN
X2                       HOLD
```

---

## Do not mis-patch

| ❌ Wrong track | Why |
|----------------|-----|
| X1 / admission | `REMOTE_RECEIPT_ACKED` never happened |
| Watchdog / timeout | `controlReady=false` is consequence |
| X2 residency | `FAILED_MEDIA_RECOVERY` is terminal appearance |
| ADR-0040 / UI | out of domain |

Future fix domain (T3 only, not now): **transport reliability / recovery delivery**.

---

## Desired semantic layers (target vocabulary)

```text
QUEUED              enqueue / dispatch intent
WRITTEN             WRITE_ACCEPTED (encode + socket present)
SENDTO_SUCCESS      SIGNAL_DATAGRAM_SENT (kernel send OK)
REMOTE_RECEIVED     peer SIGNAL_DATAGRAM_RECEIVED / REATTACH_INBOUND
RECEIPT_ACKED       REMOTE_RECEIPT_ACKED
```

Today’s `transportResult=SENT` collapses too early (at or before `WRITTEN` / non-throwing API).

---

## T1 — SENT 语义审计（desk）

### Questions

1. Who writes `transportResult=SENT`?
2. Is it write-queue accept, socket send success, or async callback?

### Desk answer (code + field)

| Step | Where | Behavior |
|------|-------|----------|
| WRITE_ACCEPTED | `UdpSignalingChannel.sendInternal` → `TransportCapabilityTrace.datagramWriteAccepted` | Always logged **before** `sendto` |
| sendto | `DatagramSocket.send` in `runCatching` | On failure: `SIGNAL_DATAGRAM_SEND_FAILED` only |
| SIGNAL_DATAGRAM_SENT | same method, **only if** `result.isSuccess` | Absent on ENETUNREACH (field confirmed) |
| Exception to caller | **Not thrown** on sendto failure | `send()` returns normally |
| `RECOVERY_REATTACH_SENT` / `transportResult=SENT` | `TalkbackCoordinator.executeRecoveryReattachSend` `runCatching { signalingChannel.send(...) }.onSuccess` | Treats non-throwing return as success |

**Conclusion T1:** `SENT` = “`signalingChannel.send` returned without throwing”, **not** `SENDTO_SUCCESS`. Field: simultaneous `WRITE_ACCEPTED` + `SEND_FAILED ENETUNREACH` + `RECOVERY_REATTACH_SENT` with zero `SIGNAL_DATAGRAM_SENT` for `CONFERENCE_REJOIN`.

**T1 status:** DESK PASS (mapping located). Optional: one more field corroboration only if needed — not required to open ADR.

---

## T2 — ENETUNREACH 后 obligation 生命周期 — **PASS (desk)**

**Question:** Why no new `CONFERENCE_REJOIN` after `sendto ENETUNREACH`?

### Timeline (attempt 14)

| t | Fact |
|---|------|
| 15:02:37.781 | `SEND_FAILED ENETUNREACH` + false `TRANSPORT_SENT` · only one `REATTACH_ENQUEUED` |
| after fail | `obligationOpen=true` · `ATTEMPT_DISPATCHING` · `attemptTerminal=false` · **no retry scheduled** |
| 15:02:55.019 | `ROUTE_CONVERGED` → wants `DISPATCH_REATTACH` |
| 15:02:55.020 | **`approved=false rejectReason=transport_in_flight`** |
| 15:02:55.117 | `REATTACH_MEDIA_ALREADY_LIVE` · handshake marked without receipt |
| 15:02:55.333 | `EDGE_RECOVERED` / `ATTEMPT_SUCCEEDED` / obligation closed · `deliveryPhase=NONE` |
| — | **No** `FAILED_MEDIA_RECOVERY` / `ATTEMPT_TIMEOUT` this episode |

### Classification

| Case | Verdict |
|------|---------|
| **A** `TRANSPORT_RETRY_MISSING` | **PRIMARY** — fail while obligation open; no rejoin retry |
| **B** attempt closed | **OUT** — attempt still `DISPATCHING` after `SEND_FAILED` |
| **C** `RECOVERY_INTENT_LOST_AFTER_MEDIA_RESTORE` | **SECONDARY** — media path closes obligation without successful rejoin |
| Compound | False `SENT` → `transport_in_flight` blocks restore-time redispatch |

### ADR gate

```text
Transport ADR     ADR-0042 ACCEPTED FOR IMPLEMENTATION REVIEW
  doc: talkback/docs/adr/0042-recovery-reattach-transport-delivery-semantics.md
  INV-T1..T4 + failure-state ownership ACCEPTED
  runtime FROZEN · field PAUSED
  next: implementation plan review (not code)

X1 / X1-B         DO NOT OPEN
```

---

## T3 — 最小修复方向（暂不实现）

If confirmed:

```text
sendto ENETUNREACH
+ obligation still active
+ network restored
```

Then future work belongs to:

```text
transport reliability / recovery delivery
```

Possible shape (design only):

- surface `SENDTO_FAILED` to recovery (do not mark `TRANSPORT_SENT`)
- retry `CONFERENCE_REJOIN` while obligation open after link restore
- keep X1 / ADR-0040 / UI / watchdog untouched

**Gate to ADR:** T1 frozen + T2 A/B/C decided → then transport ADR (new), not X1 reopen.

---

## One-line summary

> X1 validation did not fail; X1 was never entered. Recovery stuck because the reattach control message was falsely marked `SENT` while `sendto` failed with `ENETUNREACH`, and there was no post-restore retry.
