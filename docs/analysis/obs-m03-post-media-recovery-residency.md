# OBS-M03-POST-MEDIA-RECOVERY-RESIDENCY

**Status:** OPEN (read-only) — **P0/P1 FIELD ADJUDICATED** (attempt-2 desk test PASS)  
**Date:** 2026-08-08  
**Trigger evidence:** `logs/rca-m03-fence-validation-20260807-215855/` (Attempt 2, env valid)  
**Parents:** [RCA-M03](./rca-m03-wifi-reconnect-membership-divergence.md) · ADR-0030 failed-media residency · ADR-0040 (exclusion)

**Goal:** After media recovers, does `FAILED_MEDIA_RECOVERY` residency exit — and if not, is the exit trigger incomplete?

```text
media recovered
        ↓
failed residency exit   ← THIS OBS
        ↓
presence projection update   (downstream only; not the defect)
```

---

## 1. Architecture verdict (frozen)

| Item | Verdict |
|------|---------|
| ADR-0040 regression | **No** — positive field evidence (below) |
| Recovery ownership / watchdog | **Healthy** — PR-LIFE-1 path executed |
| Media lifecycle | **Healthy** — ICE/media CONNECTED, receivePathLive |
| Presence / UVCP projection | **Not implicated** — faithfully renders edge phase |
| Suspected gap | **failed-media residency exit contract** (edge-triggered) — **CONFIRMED** (field + desk test) |
| Secondary | M01 **CONTROL_HANDSHAKE_PENDING** (not UNWIRED) — same upstream class |

### ADR-0040 positive evidence (Attempt 2)

```text
transport_recovered_on_ice_connected
        ↓
resumeAttemptOwnershipAfterCapabilityRestore (ICE_CONNECTED_L2)
        ↓
resumeFromDeferred=true
        ↓
ROUTE_CONVERGED wakeup
        ↓
EDGE_RECOVERED (M02→M03)
```

**Do not write:** Recovery broken · Watchdog bug confirmed · MediaState sticky · presence sticky as root cause.

**Correct naming:**

```text
failed-media residency exit may require edge-triggered event
level-based residency recheck missing (hypothesis)
```

---

## 2. Derived-fact model (do not re-misclassify)

```text
MediaState + failedMediaResidency
        ↓
MediaUsabilityFact.isUnavailable()   ← pure function, no store
        ↓
mediaUnavailable (derived)
        ↓
UVCP / finalPresence
```

Field paradox:

```text
media=CONNECTED
mediaUnavailable=true
```

**Means only:**

```text
mediaState recovered
AND record.phase == FAILED_MEDIA_RECOVERY   (residency not exited)
```

Not a SET/CLEAR latch. Not a projection lie. Every layer follows its contract.

---

## 3. Split phenomena (do not merge)

| Node | Pattern | Class |
|------|---------|-------|
| **M02** | media OK · `obligationOpen=false` · `failedMediaResidency=true` · finalPresence=RECONNECTING | **Primary OBS** — residency exit |
| **M01** | media OK · `obligationOpen=true` · `CONTROL_RECONCILIATION_PENDING` · finalPresence=SYNCING | **Secondary** — design-valid SYNCING; ask why reconciliation does not exit |
| **M03 UI** | shows peers reconnecting while media path up | Downstream of the two above |

---

## 4. Hypothesis H-RES-1 (highest probability)

```text
T0  attempt_timeout → FAILED_MEDIA_RECOVERY (obligation still open)
T1  ICE CONNECTED while obligationOpen=true → completion path, no residency exit
T2  observation window → obligation closes; phase stays FAILED_MEDIA_RECOVERY
T3  ICE remains CONNECTED → no new edge → onIceConnected not re-entered
    → failedMediaResidency remains true
```

Exit authority today appears concentrated in `onIceConnected()` when `!edgeObligationOpen()` → phase=CONNECTED. If that transition only fires on ICE **edges**, level-stable CONNECTED after obligation close never clears residency.

**Status:** CONFIRMED — field attempt-2 (`logs/rca-m03-fence-validation-20260807-215855/`) + desk test `failedMediaRecovery_obligationClosedWhileIceStable_residencyPersists`.

---

## 4b. P0 adjudication — why enter FAILED_MEDIA_RECOVERY? (attempt-2)

**Not** `MEMBERSHIP_AUTHORITY_UNWIRED`. Membership probe was **CHECKED** and converged before attempt-7 timeout.

**Primary blocker:** `CONTROL_HANDSHAKE_PENDING` (`controlHandshakeCompleted=false`).

```text
M03→M02 attempt 7
  phase = REATTACH_REQUESTED          ← controlPlaneStarted() == false
  membershipEpochConverged = true
  iceConnected (level) ≈ true at timeout
  failureClass = CONTROL_RECONCILIATION_TIMEOUT
        ↓
  FAILED_MEDIA_RECOVERY (21:59:57)
        ↓
  ICE stable CONNECTED (21:59:58+, no new onIceConnected edge)
        ↓
  OBLIGATION_DEADLINE close (22:00:27)
        ↓
  phase stays FAILED_MEDIA_RECOVERY · mediaUnavailable=true · finalPresence=RECONNECTING
```

**Contract question (P0):** attempt timed out while still in `REATTACH_REQUESTED` — reattach dispatched but **REATTACH_ACCEPTED never arrived** before watchdog budget. `shouldDeferWatchdogForControlReconciliation` requires `controlPlaneStarted()==true` to defer; when handshake never crosses `REATTACH_ACCEPTED`, watchdog does **not** defer → `attempt_timeout` even though membership already converged.

M01→M03 (secondary): same class — `CONTROL_RECONCILIATION_PENDING` with `iceConnected=true`, `controlHandshakeCompleted=false`.

**Do not patch residency first** — that would mask a real control-handshake stall.

---

## 4c. P0+ adjudication — why no `REATTACH_ACCEPTED` on attempt-7? (observation only)

**Evidence:** `logs/rca-m03-fence-validation-20260807-215855/` · M03→M02 · `nonce=c9916187-54d7-4b26-a1f2-82da527229d9`

### Bilateral chain (frozen)

```text
21:59:44  attempt-7 opened (supersede from attempt-6)
          ICE_CONNECTED_L2 · ownership resumed · membership converged=true
          decision=WAIT_FOR_CONTROL_PLANE

21:59:47.220  RECOVERY_REATTACH_ENQUEUED (M03→M02)
21:59:47.226  SIGNAL_DATAGRAM_SENT (CONFERENCE_REJOIN)           ← A: generated + sent
21:59:47.228  RECOVERY_REATTACH_REQUESTED · TRANSPORT_SENT
21:59:47.251  RECOVERY_REATTACH_RECEIPT · REMOTE_RECEIPT_ACKED   ← B: transport delivered

21:59:47.280  M02 inbound offer arrives at M03
              NEGOTIATION_OWNER_CONFLICT canonicalOwner=M03 wireOwner=M02
              RECOVERY_OFFER_RECEIVED decision=DROP_OWNERSHIP_CONFLICT   ← bilateral glare

21:59:49.697  M02 RECOVERY_REATTACH_INBOUND (same nonce) — 2.5s after send
              M02 REATTACH_ACCEPTED on M03 edge (attempt-4) — NOT on M03→M02 edge

21:59:57.520  M03 attempt-7 ATTEMPT_TIMEOUT · phase still REATTACH_REQUESTED
              failureClass=CONTROL_RECONCILIATION_TIMEOUT
              M03: no REATTACH_ACCEPTED for remote=M02 in entire run
```

### Ruled out / ruled in

| Hypothesis | Verdict |
|------------|---------|
| A. Reattach never sent | **RULED OUT** — `RECOVERY_REATTACH_SENT` + `TRANSPORT_SENT` |
| B. Transport lost | **RULED OUT** — `REMOTE_RECEIPT_ACKED` on M03 within 25ms |
| C. Remote rejected | **PARTIAL** — M02 eventually `REATTACH_INBOUND`; acceptance on **M02→M03** edge only |
| D. Ack lost | **RULED OUT** for transport receipt; **no `REATTACH_ACCEPTED` on initiator edge** |
| **E. Bilateral recovery glare** | **RULED IN** — competing owners; M03 drops M02 offer; initiator edge never crosses `REATTACH_ACCEPTED` |

### One-sentence P0+ answer

> Attempt-7 **did dispatch and deliver** reattach, but **bilateral recovery coordination failed**: M02's competing recovery offer was dropped on M03 (`DROP_OWNERSHIP_CONFLICT`), and M03's M02-edge never received `REATTACH_ACCEPTED` before watchdog expiry — despite transport-level success and later M02 inbound processing.

### Observation sufficiency

Existing tokens were **sufficient** for this archaeology (no new instrumentation required for P0+):

`RECOVERY_REATTACH_ENQUEUED` · `RECOVERY_REATTACH_SENT` · `RECOVERY_REATTACH_RECEIPT` · `RECOVERY_REATTACH_INBOUND` · `RECOVERY_REATTACH_ACCEPTED` · `RECOVERY_OFFER_RECEIVED` · `NEGOTIATION_OWNER_CONFLICT` · `DROP_OWNERSHIP_CONFLICT`

### Contract implication (observation → future ADR-X1 input)

Watchdog treated `REATTACH_REQUESTED` + `REMOTE_RECEIPT_ACKED` as timeout-eligible while `controlPlaneStarted()==false`. Transport receipt ≠ control handshake admission. When bilateral recovery is active, **receipt ack without `REATTACH_ACCEPTED` on initiator edge** is not a safe terminal precondition.

**Not authorized:** ADR draft · logic change · residency patch.

## 5. Audit questions

### Q1 — Failed media residency exit

Capture per edge:

```text
failedEnteredAt
iceConnectedAt
obligationClosedAt
phaseExitAt   (phase → CONNECTED | null)
```

**Confirm edge-trigger gap if:**

```text
iceConnectedAt < obligationClosedAt
AND phaseExitAt == null
AND soak ≥ 5 min with ice remaining CONNECTED
```

### Q2 — Level recheck authority

Answer: **who may mutate** `FAILED_MEDIA_RECOVERY → CONNECTED`?

- If only `onIceConnected()` → classic edge-trigger risk confirmed
- Check whether `DIGEST_REFRESH` / `notifyChanged` / `factsForSession` can change phase (expected: no — they read phase)

### Q3 — Control reconciliation (M01 secondary) — ADJUDICATED

Captured `mismatchReason()` from attempt-2 logs:

```text
CONTROL_HANDSHAKE_PENDING     ← primary (both M01 and M03 edges)
MEMBERSHIP_AUTHORITY_UNWIRED  ← NOT observed this run
MEMBERSHIP_EPOCH_MISMATCH     ← transient early; converged=true before attempt-7 timeout
```

Touches RCA-M03 **membership semantics** only indirectly (membership converged; handshake did not). Still **not** ADR-0040.

---

## 6. Field method (next)

```text
1. Three-party conference OPERATIONAL (no USER_LEAVE)
2. M03 WiFi OFF ~15–30s → ON
3. Soak ≥ 5 minutes after first media CONNECTED post-flap
4. Collect Talkback logs all three devices
5. Adjudicate Q1–Q3 only
```

Log dir:

```text
talkback/logs/obs-m03-post-media-residency-YYYYMMDD-HHMMSS/
```

**Mandatory greps:**

```text
FAILED_MEDIA_RECOVERY
RECOVERY_EDGE_RECOVERED
RECOVERY_OBLIGATION_CLOSE
onIceConnected / ICE_CONNECTED / noteMediaRestored
CONTROL_RECONCILIATION
CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED
mismatchReason / MEMBERSHIP_AUTHORITY
REACHABILITY_PROBE (media= / mediaUnavailable= / finalPresence= / edgeRecoveryPhase=)
```

Attempt-2 limitation: collectors stopped ~22:02 — observed sticky ≥2 min, **not** proven permanent. 5-minute soak closes that gap.

---

## 7. Authorization boundary

```text
AUTHORIZED:     read-only audit · 5 min soak · Q1–Q3 adjudication
NOT AUTHORIZED: recovery phase hotfix · completion predicate change
                ADR-0030 residency mutation without contract note
                UI / PR-UI-2 merge as fix · membership self-heal
                reopen ADR-0040 / RNA / completion
```

If H-RES-1 confirmed → draft **failed-media residency exit contract** (ADR-0030 amendment or small ADR) before any level-recheck PR. Effort class: find cause low–medium · code change low–medium · contract impact medium. Not ADR-0040-scale.

---

## 8. Status board

```text
ADR-0040 Recovery Lifecycle
    VERIFIED
    positive field evidence:
      resumeFromDeferred
      ownership resumed (ICE_CONNECTED_L2)
      EDGE_RECOVERED (M02)

RCA-M03 Convergence Fence
    H1 NOT REPRODUCED (attempt-2)
    keep independent — do not enlarge this OBS into fence RCA

OBS-M03-POST-MEDIA-RECOVERY-RESIDENCY
    OPEN (read-only)
    P0 ADJUDICATED: CONTROL_HANDSHAKE_PENDING → attempt_timeout
    P0+ ADJUDICATED: bilateral recovery glare; reattach sent+delivered; no REATTACH_ACCEPTED on initiator edge
    P1 CONFIRMED: residency exit edge-trigger gap (desk test PASS)

Findings:
    mediaUnavailable is DERIVED (MediaUsabilityFact)
    projection not implicated
    post-obligation-close soak: media=CONNECTED · obligationOpen=false ·
      edgeRecoveryPhase=FAILED_MEDIA_RECOVERY · mediaUnavailable=true (≥2 min)

Hypothesis H-RES-1:
    CONFIRMED — failed-media residency exit requires new ICE edge or open-obligation supersede

Upstream driver (co-primary):
    control handshake never reaches REATTACH_ACCEPTED before attempt budget

PR-UI-2
    HOLD (UVCP inputs not yet cleared)
```
