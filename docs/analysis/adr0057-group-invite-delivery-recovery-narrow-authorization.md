# ADR-0057 Track C — Group Invite Delivery Recovery Narrow Authorization (F6 / GIDR)

## Status

**AUTHORIZED — IMPLEMENTATION CUT** (2026-08-31)

```text
Finding F6:
  GROUP INVITE DELIVERY RECOVERY
  CONFIRMED IMPLEMENTATION GAP

Classification:
  NARROW IMPLEMENTATION DEFECT

Implementation cut:
  AUTHORIZED

Required:
  GIDR-T1..T4
  GIDR-EG1..EG6

Field:
  FIELD RECOVERY PASS / CLOSED FOR THIS DEFECT
  Evidence: talkback/logs/adr0057-track-c-case-a-20260831-173143/

F4 / Outbound GIE:
  FIELD PASS / CLOSED

F5 / IGIE:
  FIELD PASS / CLOSED

M03 IGIE @ 165729:
  NOT EXERCISED (historical — correct for that run)

M03 IGIE @ 173143:
  EXERCISED / PASS

Case A End-to-End:
  PARTIAL / OPEN

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [Post-IGIE delivery residual audit](./adr0057-post-igie-group-invite-delivery-residual-audit.md)
- [Outbound GIE authorization](./adr0057-group-invite-execution-narrow-authorization.md)
- [Inbound IGIE authorization](./adr0057-inbound-group-invite-execution-narrow-authorization.md)
- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)

---

## Frozen gap

```text
GROUP_INVITE delivery obligation OPEN
→ valid invite constructed
→ DatagramSocket.send() succeeds locally
→ handoff success
→ remoteSignalingInFlight / INVITING
→ packet happens not to reach peer
→ no GROUP_ACCEPT
→ late-peer reconciliation continues
→ no new wire opportunity
→ stale timeout path not exercised
→ obligation effectively stranded
```

**Not the fix target:** UDP reachability / reliable UDP / datagram ACK subprotocol.

**Fix target:** reconnect existing `OutboundGroupInviteAttempt` stale machinery to late-peer reconciliation so unaccepted handoffs regain a **bounded** send opportunity.

Positive completion evidence (application layer, already frozen):

```text
GROUP_INVITE → GROUP_ACCEPT
```

No `GROUP_INVITE_DATAGRAM_ACK` or transport-level ACK-of-UDP.

---

## GIDR-T1 — Local handoff does not satisfy delivery obligation

A successful local `GROUP_INVITE` datagram handoff **must not** by itself satisfy the peer admission / invite delivery obligation.

Until authoritative peer-side acceptance evidence is observed, the obligation remains unresolved.

```text
OPEN obligation ≠ continuous retransmission
```

## GIDR-T2 — GROUP_ACCEPT is positive completion evidence

A valid `GROUP_ACCEPT` correlated to the outstanding GROUP invite attempt **may** satisfy the delivery obligation.

`HELLO`, `HEARTBEAT`, `FACT_*` traffic, local send success, or `PEER_EDGE` observation **must not** substitute for `GROUP_ACCEPT`.

Equivalent already-frozen accepted-session evidence may satisfy completion; do not invent new authority for this cut.

## GIDR-T3 — Unaccepted attempt must regain a bounded send opportunity

If a locally handed-off `GROUP_INVITE` remains unaccepted for the **existing bounded attempt window** (`ATTEMPT_STALE_TIMEOUT_MS`), the still-open obligation **must** regain an execution opportunity.

A previous successful local handoff **must not** permanently suppress that future opportunity.

**First choice:** wire existing stale/terminal machinery — **not** a new blind retry timer.

```text
INVITE wire handoff
→ INVITING
→ no GROUP_ACCEPT
→ existing attempt becomes STALE
→ reconciliation observes stale/open obligation
→ new attempt opportunity
→ GIE
→ valid GROUP_INVITE again
```

Forbidden:

```text
every HELLO → resend INVITE
```

## GIDR-T4 — Retry is bounded and deduplicated

Repeated late-peer reconciliation **must not** create an invite burst.

At most one active GROUP invite attempt for the same peer / channel / relevant lineage may own the current delivery opportunity.

Retry becomes eligible only after the prior attempt is accepted, terminal, or stale according to existing bounded attempt semantics.

Preserves GIE intent / attempt / wire separation.

---

## Minimum fixtures (GIDR-EG1..EG6)

| Gate | Requirement |
|------|-------------|
| **EG1** | First `GROUP_INVITE` handoff + `GROUP_ACCEPT` → obligation satisfied; no retry |
| **EG2** | First handoff success but simulated peer no receive / no `GROUP_ACCEPT` → obligation **not** closed |
| **EG3** | Existing stale window elapses → new execution opportunity → second valid `GROUP_INVITE` |
| **EG4** | Second `GROUP_ACCEPT` → obligation closed; subsequent late-peer → no further wire |
| **EG5** | Heavy reconcile / HELLO during active attempt window → no INVITE burst |
| **EG6** | GIE + IGIE + GBC/CSO/ACQ + release-ordering regression PASS |

**Negative assertion (all EG):**

```text
DatagramSocket.send() success
  → MUST NOT be asserted as peer delivery success
```

---

## Authorized scope (when cut is approved)

- Existing `OutboundGroupInviteAttempt` lifecycle
- Existing stale / terminal evaluation (`ATTEMPT_STALE_TIMEOUT_MS`)
- Late-peer reconciliation → stale attempt reevaluation → GIE execution opportunity
- `GROUP_ACCEPT` → attempt / delivery obligation completion
- Bounded retry execution opportunity (via stale, not blind periodic resend)
- Diagnostic semantic correction (`sent=1` ≠ wire ≠ delivery)
- GIDR-EG1..EG6

## Not authorized

- Generic reliable UDP
- New transport ACK protocol (`GROUP_INVITE_DATAGRAM_ACK` or similar)
- Periodic blind `GROUP_INVITE` retransmission
- ICE retry changes
- Socket / rebind redesign
- Payload fragmentation / MTU tuning / SDP compression (2040B is risk signal only; run `163552` proves path-capable)
- Generation changes
- GIE / IGIE semantic reopening
- Membership policy changes
- `transmitReady` changes

---

## Field gate (Case A re-run)

Do **not** require a natural UDP miss for PASS.

**Normal completion (sufficient for implementation smoke):**

```text
INVITE #1
→ M03 receives
→ IGIE
→ GROUP_ACCEPT
```

**Full GIDR recovery (stronger; not required if miss does not recur):**

```text
INVITE #1 local handoff
→ no M03 receive / no ACCEPT
→ bounded stale
→ INVITE #2
→ M03 receive
→ GROUP_ACCEPT
```

Deterministic miss/recovery proof is primarily **fixture** (EG2–EG4). Absence of natural miss on field re-run is **not** implementation FAIL.

---

## Sign-off

```text
☑ F6 audit complete
☑ Layer A / Layer B classification frozen
☑ GIDR-T1..T4 frozen
☑ GIDR-EG1..EG6 frozen
☑ Implementation cut authorized
☑ GIDR-EG1..EG6 PASS (unit / regression)
□ GIDR FIELD CONFORMANCE (Case A)
```

### Implementation seam (2026-08-31)

| Component | Change |
|-----------|--------|
| `GroupInviteDeliveryRecoverySupport` | Reconcile gate: ACTIVE / SATISFIED / PermitRetry; staleness delegated to attempt lifecycle |
| `OutboundGroupInviteAttemptSupport` | `isDeliverySatisfied`, `markDeliverySatisfiedFromGroupAccept` |
| `TalkbackCoordinator.deliveryRetryInvitees` | Late-peer path re-dispatches after stale when admission incomplete |
| `TalkbackCoordinator.tryReinviteGroupPeerPairwise` | Gate before dispatch; `dispatchAccepted` log (not wire/delivery) |
| `TalkbackCoordinator.handleGroupAccept` | GROUP_ACCEPT → delivery satisfied on outbound attempt |
| `GroupInviteDeliveryRecoveryLog` | `GIDR_*` diagnostics |

### Fixture mapping

| Gate | Test |
|------|------|
| EG1 | `Adr0057GroupInviteDeliveryRecoveryFixturesTest.gidrEg1_handoffAndGroupAccept_satisfiedNoRetry` |
| EG2 | `gidrEg2_handoffWithoutAccept_staleLeavesObligationOpen` |
| EG3 | `gidrEg3_staleReconcile_grantsExactlyOneWireOpportunity` |
| EG4 | `gidrEg4_secondAcceptClosed_noFurtherWireOnReconcile` |
| EG5 | `gidrEg5_activeWindow_highFrequencyReconcile_zeroAdditionalWire` |
| EG6 | `gidrEg6_gieProvisionGate_unchangedByGidr` + GIE/IGIE fixture suites |
