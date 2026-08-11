# CONFERENCE-INSTANCE-ADMISSION-001 Closure Memo

**Status:** `CLOSED - NOT REPRODUCED` (2026-08-11)  
**Issue:** `CONFERENCE-INSTANCE-ADMISSION-001`  
**Type:** Architecture Investigation Closure  
**Does not:** amend Admission contracts | authorize CAA | change join/accept behavior | reopen WiFi recovery domains

**Evidence runs:**

| Run | Path | Result |
|-----|------|--------|
| Case 0 — Cold Host Baseline | `logs/conference-admission-obs-20260811-180502` | PASS |
| Case 2 — Cancel → Recreate | `logs/conference-admission-obs-20260811-180502` | PASS |
| Stress churn | `logs/conference-admission-obs-20260811-181039` | PASS |
| Legacy log replay | `logs/join-stability-m02-20260811-170942` | Classified as participant accept path |

---

## Original symptom

> M02 点击 Meeting 后进入 Connecting，怀疑 Host 无法建立会议。

Operator belief: M02 was initiating meetings and stuck in CONNECTING.  
Initial hypothesis: Host identity corruption, conference instance split, or channel binding ambiguity.

---

## Investigation process

### Phase 1 — Observation PR (log-only)

Instrumentation added (no behavior change):

- `JOIN_MEETING_TRACE` — intent / outcome, `chosenPath`, `authorityModuleId`, `shouldLocalInitiateConference`
- `ADMISSION_DECISION`
- `SESSION_CREATED` — identity fields (`role`, `initiator`, `writer`)
- `CHANNEL_SESSION_BIND` / `CHANNEL_SESSION_SNAPSHOT`
- `READINESS_BINDING` — `selectedSession`
- Lifecycle: `SESSION_TERMINATED`, `SESSION_REMOVE_BEGIN/COMPLETE`, `MEDIA_RUNTIME_RELEASE`

Target evidence chain:

```text
Intent
  ↓
Action
  ↓
Session
  ↓
Binding
  ↓
Readiness
  ↓
Media
```

### Phase 2 — Field validation

Observation APK deployed to M01 / M02 / M03 (SSID `happy`).

### Phase 3 — Legacy log replay

Re-interpreted `join-stability-m02-20260811-170942` using equivalent pre-observation fields (`acceptGroupInvite`, `SESSION_CREATED`, `CONFERENCE_RUNTIME_DECISION`).

---

## Key evidence

### Case 0: Cold Host Baseline — PASS

```text
USER_CREATE
  ↓
HOST session
  ↓
LIVE
```

M02: `chosenPath=CREATE`, `localRole=HOST`. Host create path healthy.

### Case 2: Cancel → Recreate — PASS

```text
TERMINATED
  ↓
REMOVE_COMPLETE
```

Lifecycle closure confirmed. No stale conference, old instance leak, or authority residue.

### Stress churn — PASS

Multi-round:

```text
CREATE → LIVE → HANGUP → REMOVE_COMPLETE
```

~8 min aggressive churn (Run 2): M02 11× `chosenPath=CREATE` all HOST; 0× binding errors, 0× lifecycle gaps, 0× REJOIN/WAIT on M02 create path.

### Legacy log replay — participant accept path

Log: `join-stability-m02-20260811-170942`  
`RUN_META` already noted: `log_truth=M01_sent_invites; M02_accepted_as_participant`.

Actual event model:

```text
M01
 |
 | meshCallInternal() — HOST
 |
 | invite
 |
 v
M02
 |
 | pending invite → acceptGroupInvite()
 |
 v
PARTICIPANT session
 |
 | WAIT_HOST_ICE (hostIce=CLOSED → CONNECTED)
 |
 v
ACTIVE (~0.5–2s)
 |
 | REMOTE_TERMINATION (M01 hangup)
 v
(next round × 4)
```

Not:

```text
M02 Host stuck CONNECTING
```

But:

```text
M02 Participant join path
```

M02: 0× `meshCallInternal` / `solo_host`; 4× `acceptGroupInvite` as PARTICIPANT (`host=false`, `hostModule=M01`).  
`RECOVERY_REATTACH` on M02 in legacy log: 0.

---

## Rejected hypotheses

| Hypothesis | Result |
|------------|--------|
| Host identity corruption | Rejected |
| Host CREATE failure | Rejected |
| Conference instance split | Rejected |
| Channel binding wrong session | Rejected |
| Lifecycle not cleaned up | Rejected |
| Recovery reattach caused this symptom | No evidence |

---

## Root finding (not Admission defect)

**Operation model vs system path divergence** — potential UX/intent ambiguity, not architecture failure.

User mental model:

```text
点击 Meeting = 我要创建会议
```

System may execute:

```text
pending invite + acceptGroupInvite = 我要加入 M01 的会议
```

User sees `Connecting...` and believes "I cannot start a meeting."  
System is legitimately: "You are joining M01's meeting as participant."

This is the core finding of the investigation. The originally suspected Admission layer is healthy.

---

## Final adjudication

```text
Conference Admission     PASS
Conference Lifecycle   PASS
Conference Identity    PASS
Conference Binding     PASS

Issue root cause       UX intent ambiguity (potential)
Action                 Archive
```

CAA (Conference Instance Admission Authority) is **not warranted** — normal scenarios show no instance split; lifecycle and binding behave correctly.

---

## Follow-up track (separate, low priority)

### CONFERENCE-UX-INTENT-DIVERGENCE-001

**Status:** `BACKLOG / LOW PRIORITY`  
**Scope:** Observation only — do not change join/accept priority or UI behavior yet.

Possible divergence sources (unconfirmed):

- **Case A:** Pending invite priority — user taps Meeting while `pendingInvite != null` → `acceptGroupInvite()`
- **Case B:** UI does not express incoming-invite context
- **Case C:** Race between user CREATE tap and accept handler

Suggested observation fields (future PR `conference-intent-observation-only`):

```text
INTENT_TRACE
  requestedAction
  pendingInvite
  authorityModuleId
  shouldLocalInitiate
  chosenPath

INTENT_CONFLICT  (when requested=CREATE && pendingInvite!=null)
  channel
  requested=CREATE
  chosen=ACCEPT_PENDING
  inviteFrom
  reason=PENDING_INVITE_PRIORITY
```

Do **not** yet:

- Change CREATE vs ACCEPT_PENDING priority (product decision)
- Rename Meeting button semantics (no usage data)

### Participant join latency

Separate performance track if M01 slow connect as PARTICIPANT is observed (`RECOVERY_REATTACH`, `WAIT_HOST_ICE` duration). Not the root cause of `170942`.

---

## Investigation template (retained)

For any future Conference "stuck CONNECTING" report, investigate in order:

```text
1. Intent     — What did the user intend?
2. Action     — What did the system actually execute?
3. Session    — HOST or PARTICIPANT?
4. Binding    — Which session does UI/runtime select?
5. Readiness  — Why is uiReady false?
6. ICE        — Media/negotiation failure (last, not first)
```

Do not start from ICE or generic state machine.

**Minimum log grep for fast triage:**

```bash
grep -E "JOIN_MEETING_TRACE|INTENT_CONFLICT|ADMISSION_DECISION|SESSION_CREATED|CHANNEL_SESSION_BIND|READINESS_BINDING|SESSION_REMOVE|RECOVERY_REATTACH"
```

---

## Closure statement

`CONFERENCE-INSTANCE-ADMISSION-001` is closed at the architecture investigation layer. No Admission refactor, CAA, or binding fix is authorized by this investigation.

Primary value: proved the suspected architecture layers are healthy and avoided an incorrect CAA/Admission refactor. Secondary value: identified UX/intent divergence as the plausible explanation for operator misclassification of `170942`.
