# Post-ADR-0040 Control Admission Validation Run Card

**Status:** AUTHORIZED (read-only · no behavior change)  
**Case:** `post-adr0040-control-admission-validation`  
**Date:** 2026-08-08  
**Parent:** [X1 mini design note](./post-adr0040-control-admission-contract-mini-design.md) · [OBS-M03](./obs-m03-post-media-recovery-residency.md)  
**RCA boundary:** Post ADR-0040 Control Admission only — **do not enlarge RCA-M03 fence scope**  
**Evidence freeze:** `d16029e` on `main` · **current APK only**

**Goal:** Directed validation of bilateral recovery **control admission contract** assumptions under M03 WiFi flap. **Not** bug hunting. **Not** implementation.

```text
observe → prove → contract → implement
```

**Single validation question:**

> After `REMOTE_RECEIPT_ACKED`, does control admission enter the correct evaluation path (including glare policy), rather than proceeding directly to attempt timeout?

---

## Architecture boundary (frozen)

```text
IN SCOPE:
  M03→peer initiator edge control admission chain
  delivery → reevaluation → glare → boundary / timeout semantics
  attempt-level timeline (t0–t4)

OUT OF SCOPE:
  residency exit fix (X2 — HOLD)
  UI / presence / UVCP projection
  SMS / messaging features
  membership fence / RCA-M03 H1
  ADR-0040 ownership regression re-litigation
  recovery FSM / completion predicate changes
  watchdog budget enlargement
```

**Do not write during adjudication:**

```text
❌ "增加 timeout"
❌ "UI 显示问题"
❌ "network unstable"
❌ "retry more"
❌ Recovery broken · Watchdog bug confirmed
```

---

## Problem domain (frozen under RCA-M03)

```text
RCA-M03
 └── Post ADR-0040 Control Admission

      CONFIRMED (do not re-litigate):
        ownership OK
        transport OK
        membership OK
        failure entry understood (attempt-7 archaeology)

      TO VALIDATE (this run):
        receipt → admission reevaluation wiring
        glare → E2 shortcut suppression policy
        timeout legitimacy vs admission state
```

---

## Scenario

### Topology

| Role | Module | Serial |
|------|--------|--------|
| Host | M01 | `HTUBB21B09220661` |
| Peer | M02 | `2d73067a` |
| DUT  | M03 | `MDX0220416001963` |

- SSID: **`happy`** only (not `happy_5G`)
- Channel: `CH-01` · three-party conference · OPERATIONAL before flap

### Trigger

```text
T0  Three-party conference stable (no USER_LEAVE)
T1  M03 WiFi OFF ~15–30s → ON (only DUT flaps)
T2  Soak ≥ 5 min after first post-flap ICE CONNECTED on any edge
```

### Expected scenario class

```text
M03 recovery attempt(s)
        +
M02 competing recovery activity
        →
bilateral recovery glare (may or may not reproduce — absence is also data)
```

Prior attempt-2 (`logs/rca-m03-fence-validation-20260807-215855/`) exhibited glare on M03→M02 attempt-7. This run **validates contract assumptions**, not mandatory reproduction.

---

## Observation matrix

### O1 — Delivery → Reevaluation

**Chain:**

```text
RECOVERY_REATTACH_ENQUEUED
        ↓
RECOVERY_REATTACH_SENT / SIGNAL_DATAGRAM_SENT
        ↓
RECOVERY_REATTACH_RECEIPT · REMOTE_RECEIPT_ACKED
        ↓
?  admission reevaluation
```

**Must answer:** Does receipt trigger admission-chain reevaluation?

**Primary grep tokens:**

```text
RECOVERY_REATTACH_RECEIPT
REMOTE_RECEIPT_ACKED
RECOVERY_REEVALUATE
RECOVERY_CONTROL_PLANE_REQUIRED
reattachMediaAlreadyLive
RECOVERY_CONTROL_PLANE_BOUNDARY
REATTACH_MEDIA_ALREADY_LIVE
```

**Equivalent observations** (any satisfies O1 "reevaluation seen"):

```text
RECOVERY_REEVALUATE ... trigger=ICE_RESTORED|REMOTE_MODULE_RECOVERED|...
RECOVERY_CONTROL_PLANE_REQUIRED
continueControlPlaneRecoveryAfterMediaRestored path logs after receipt
```

| Result | Meaning |
|--------|---------|
| Reevaluation after receipt | Receipt wired into admission chain |
| No reevaluation before timeout | **E2 trigger gap** — supports X1 hypothesis |

---

### O2 — Glare detection and E2 policy

**Observe:**

```text
RECOVERY_NEGOTIATION_OWNER_CONFLICT
RECOVERY_GLARE_DECISION
DROP_OWNERSHIP_CONFLICT
RECOVERY_OFFER_RECEIVED ... decision=DROP_OWNERSHIP_CONFLICT
canonicalOwner= wireOwner=
```

**Then observe E2 shortcut decision on initiator edge:**

```text
RECOVERY_CONTROL_PLANE_BOUNDARY
REATTACH_MEDIA_ALREADY_LIVE
reattachMediaAlreadyLiveEvidenceSatisfied (if logged)
```

| Outcome | Verdict |
|---------|---------|
| Glare detected + no E2 boundary before resolution | **Correct** — shortcut suppressed under glare |
| Glare detected + `REATTACH_MEDIA_ALREADY_LIVE` / boundary | **Risk** — E2 bypasses ownership resolution |

If no glare observed: record `GLARE_NOT_OBSERVED` — O2 is INCONCLUSIVE, not FAIL.

---

### O3 — Control boundary (initiator edge)

**Focus edge:** M03 → M02 (and M03 → M01 if recovering)

**Must look for at least one on initiator edge before terminal timeout:**

```text
RECOVERY_REATTACH_ACCEPTED   (remote=M02 on M03 logs)
RECOVERY_CONTROL_PLANE_BOUNDARY
phase transition to ICE_RESTARTING on initiator edge
controlPlaneStarted=true on RECOVERY_CONTROL_RECONCILIATION_FACT
```

**Do not treat as admission success:**

```text
REMOTE_RECEIPT_ACKED
TRANSPORT_SENT
RECOVERY_REATTACH_REQUESTED alone
```

```text
delivery != admission
```

---

### O4 — Timeout legitimacy

**Per initiator edge attempt, record timeline:**

| Marker | Event |
|--------|-------|
| t0 | `RECOVERY_REATTACH_REQUESTED` |
| t1 | `RECOVERY_REATTACH_RECEIPT` / `REMOTE_RECEIPT_ACKED` |
| t2 | `NEGOTIATION_OWNER_CONFLICT` / `DROP_OWNERSHIP_CONFLICT` (if any) |
| t3 | `REATTACH_ACCEPTED` or `CONTROL_PLANE_BOUNDARY` or explicit reject |
| t4 | `RECOVERY_ATTEMPT_TIMEOUT` / `ATTEMPT_TIMEOUT` |

**Timeout is legitimate only if before t4:**

```text
admissionRejected
OR admissionDeadlineExceeded (explicit)
OR glareResolutionBudgetExceeded (if defined in logs)
```

**X1 hypothesis confirmed if:**

```text
t1 receipt present
AND no admission reject before t4
AND no CONTROL_BOUNDARY / REATTACH_ACCEPTED on initiator edge
AND t4 timeout with failureClass=CONTROL_RECONCILIATION_TIMEOUT
```

Capture `failureClass=` from `FAILED_MEDIA_RECOVERY` line when present.

---

## Evidence table (per DUT initiator edge attempt)

| Evidence | Expected (if X1 gap) | Pass if |
|----------|----------------------|---------|
| `REATTACH_SENT` | yes | observed on ≥1 attempt |
| `REMOTE_RECEIPT_ACKED` | yes | observed on ≥1 attempt |
| Admission reevaluation after receipt | **no** (hypothesis) | documented yes/no |
| `GLARE_DECISION` / conflict | yes/no | documented |
| `CONTROL_BOUNDARY` or `REATTACH_ACCEPTED` (initiator) | **no** (hypothesis) | documented |
| `TIMEOUT_REASON` | admission-based | `failureClass` + phase at timeout captured |

---

## Pass / FAIL criteria

### PASS (validation run succeeded — hypotheses adjudicated)

Run is PASS when **all** are true:

```text
1. Clean environment (no USER_LEAVE contamination)
2. O1–O4 adjudicated with timestamps for ≥1 initiator-edge attempt
3. Evidence table complete for primary edge (M03→M02 preferred)
4. Outcome routed to one of three branches below (§ Next steps)
```

PASS does **not** mean recovery UI fixed. PASS means **contract assumptions are proven or disproven**.

### FAIL (run invalid — re-run)

```text
ENV_INVALID: USER_LEAVE / conference not three-party at flap
COLLECTOR_DEAD: missing M03 logs during soak window
INCOMPLETE: cannot adjudicate O1 or O4 for any initiator attempt
```

### Hypothesis outcomes (after PASS)

**Branch A — Receipt reevaluation gap**

```text
receipt → (no reevaluation) → timeout
```

→ X1 converges: **Missing admission reevaluation edge.**  
→ Fix class: event wiring + predicate + regression test (not global timeout).

**Branch B — Reevaluation present; glare never resolves**

```text
reevaluation → glare → no resolution → timeout
```

→ Escalate: **Bilateral negotiation ownership resolution contract** (ADR-X1 + RNA owner clarification). Slightly larger scope.

**Branch C — Admission normal; timeout reasonable**

```text
receipt → reevaluation → CONTROL_BOUNDARY or REATTACH_ACCEPTED → no spurious timeout
```

→ Re-open timing/environment as secondary; **low probability** per attempt-2.

---

## Field method

### Log directory

```text
talkback/logs/post-adr0040-control-admission-YYYYMMDD-HHMMSS/
```

### Mandatory greps (M03 primary; M02/M01 for bilateral context)

```text
RECOVERY_REATTACH_ENQUEUED
RECOVERY_REATTACH_SENT
RECOVERY_REATTACH_REQUESTED
RECOVERY_REATTACH_RECEIPT
REMOTE_RECEIPT_ACKED
RECOVERY_REEVALUATE
RECOVERY_CONTROL_PLANE_REQUIRED
RECOVERY_CONTROL_PLANE_BOUNDARY
REATTACH_MEDIA_ALREADY_LIVE
RECOVERY_REATTACH_ACCEPTED
RECOVERY_NEGOTIATION_OWNER_CONFLICT
RECOVERY_GLARE_DECISION
DROP_OWNERSHIP_CONFLICT
RECOVERY_OFFER_RECEIVED
RECOVERY_ATTEMPT_TIMEOUT
FAILED_MEDIA_RECOVERY
failureClass=
RECOVERY_CONTROL_RECONCILIATION_FACT
controlHandshakeCompleted=
REACHABILITY_PROBE
```

### Adjudication order

```text
1. ENV check (USER_LEAVE, three-party)
2. Pick primary attempt: latest M03→M02 with REATTACH_SENT + receipt
3. O1 → O2 → O3 → O4 (sequential)
4. Fill evidence table
5. Route to Branch A / B / C
6. Update status boards (OBS + mini design note)
```

---

## Authorization

```text
AUTHORIZED:     read-only field run · 5 min soak · O1–O4 adjudication
NOT AUTHORIZED: recovery FSM change · watchdog relaxation · residency clear
                UI workaround · membership fence · new instrumentation PR
```

---

## Status board

```text
X1 Control Admission Contract
    Design note           DONE (d16029e)
    Directed validation   AUTHORIZED (this card)
    ADR-X1                NOT STARTED
    Implementation        NOT STARTED

Hypothesis (pre-field):
    receipt may not enter admission reevaluation chain
    attempt-7 supports; this run proves or disproves

OBS-M03 / X2
    HOLD — downstream only; do not enlarge this run

RCA-M03 fence
    CLOSED for this track — do not merge scopes
```
