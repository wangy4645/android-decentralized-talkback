# RCA-002 — Reattach Delivery Opportunity Reacquisition

**Status:** IMPLEMENTED · unit PASS · **FIELD VERIFIED**  
Evidence: `logs/conf-same-session-rejoin-20260810-182832/`

```text
Field chain (M02 18:30:03–18:30:13):
  ARMED → EXPIRED → REACQUISITION_ELIGIBLE → REEVALUATE
  → second ARMED → OBTAINED (~38ms)
  → Conference rejoin invite (M01)
  → Conference invite reconnect accepted (M02)
  → EDGE_RECOVERED
```


```text
RCA-002
Reattach Delivery Opportunity Reacquisition

Scope:
  only SENT / EXPIRED before RECEIPT
  release oneshot transport_in_flight latch
  allow a NEW delivery attempt when dispatch opportunity exists

Allowed:
  observe Phase-2 EXPIRED (fact unchanged)
  release REATTACH_REQUESTED + TRANSPORT_SENT latch
  re-evaluate DISPATCH_REATTACH when path/dispatch gate ready

Forbidden:
  global retry / N-shot backoff center
  EXPIRED ⇒ RETRY_REQUIRED
  completion / ICE / membership / acceptance changes
  Phase-2 state machine rename (ARMED/WAITING/OBTAINED/EXPIRED stay)
```

---

## Why (field)

```text
WiFi flap
 ↓
brief unidirectional blackhole
 ↓
REATTACH SENT + ARMED
 ↓
path resumes (e.g. SIGNAL_INBOUND_RESUMED)
 ↓
EXPIRED
 ↓
transport_in_flight latch still held (REATTACH_REQUESTED)
 ↓
permanent wait — Host never gets reattach — no Conference rejoin invite
```

This is independent of `CONFERENCE_SAME_SESSION_REJOIN_ACCEPTANCE_MISSING`.

---

## Model

```text
EXPIRED ≠ FAILURE
EXPIRED ≠ RETRY_REQUIRED

Delivery observation failed
  +
remote delivery opportunity returned (or already present)
  ↓
new delivery attempt eligibility
```

Not:

```text
EXPIRED → retry()
```

---

## Markers

| Log | Meaning |
|-----|---------|
| `REATTACH_DELIVERY_PROGRESS_EXPIRED` | Phase-2 fact (unchanged) |
| `REATTACH_DELIVERY_OPPORTUNITY_REACQUISITION_ELIGIBLE` | in-flight latch released |
| `REATTACH_DELIVERY_OPPORTUNITY_WAITING` | eligible but dispatch gate not ready |
| `REATTACH_DELIVERY_OPPORTUNITY_REEVALUATE` | evaluating new attempt |
| second `REATTACH_DELIVERY_PROGRESS_ARMED` | new observation after new SENT |

---

## Acceptance patch posture

```text
ConferenceSameSessionRejoinAcceptance
  UNIT VERIFIED
  FIELD BLOCKED until RCA-002 unlocks OBTAINED → rejoin invite chain
```

---

## Code

- `ReattachDeliveryProgressFacade.onObservationExpired` seam
- `ConferenceEdgeRecoveryController.onReattachDeliveryObservationExpired`
- `RecoveryReevaluateTrigger.DELIVERY_OPPORTUNITY_REACQUIRED`
