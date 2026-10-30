# IA-002 — Conference Edge Executor Admission Protection (Bounded)

**ID:** conference-edge-executor-admission-protection-ia-002  
**Date:** 2026-08-21  
**Type:** Implementation Authorization · Bounded  
**Parent contract:** [conference-edge-executor-task-completion-contract.md](./conference-edge-executor-task-completion-contract.md) (ACCEPTED v0.1)  
**Parent track:** [conference-control-media-execution-isolation.md](./conference-control-media-execution-isolation.md)

**Prerequisites:**

| Gate | Status | Evidence |
|------|--------|----------|
| Contract v0.1 | **ACCEPTED (FROZEN)** | This authorization |
| P0.1g-A Control dispatch isolation | PASS | M02/M04 dispatch while M03 blocked |
| P0.1g-B Native SRD isolation | PASS | M03 native boundary |
| P0.1g-C Edge executor admission | FAIL | Session `52c8f036-…` · taskId=67 |
| RCA | FROZEN | `Conference edge executor starvation by unbounded task` |

---

## Status

```text
Contract v0.1                         ACCEPTED (FROZEN)
Implementation Authorization IA-002   OPENED — option A only
Scope                                 MEDIA_CRITICAL admission protection
Option B (executor refactor)          EXCLUDED
P0.1g Acceptance 1                    HOLD until IA-002 field validation
Field                                 after impl — separate authorization note if needed
```

---

## Authorization goal (narrow)

**Not:** make all edge tasks faster, rewrite `PeerMediaExecutors`, or fix WebRTC native behavior.

**Only:**

> `MEDIA_CRITICAL` (`SRD_APPLY`) must obtain edge execution opportunity within the realization window — **even when** an `OBSERVATION` task blocks or poisons the lane.

Formal acceptance chain:

```text
OBSERVATION blocks / holds lane
        |
        v
MEDIA_CRITICAL SRD_APPLY SUBMIT
        |
        v
must START within realization window
```

**Contract validates outcome, not mechanism.**

---

## Selected path: option A

```text
A  MEDIA_CRITICAL admission guarantee     AUTHORIZED (this document)
B  Edge executor architecture refactor     EXCLUDED
```

Field evidence did **not** prove `PeerMediaExecutors` architecture wrong. It proved:

```text
one edge lane
  + unbounded observation workload
  + no admission protection
  = starvation
```

---

## Authorized work packages

### IA-002-1 — Task classification

Introduce explicit **category** labels on edge tasks:

```text
MEDIA_CRITICAL
MEDIA_CONTROL
OBSERVATION
```

**First batch only** (no expansion without new authorization):

| Workload | Category |
|----------|----------|
| `SRD_APPLY` | `MEDIA_CRITICAL` |
| `refreshAudioLevel` / `getStats` | `OBSERVATION` |
| `setRemotePlaybackEnabled` | `MEDIA_CONTROL` |
| ICE apply (`addIceCandidate`, edge ICE-restart path) | `MEDIA_CONTROL` |

Replace generic `OTHER` for `refreshAudioLevel` at submit site.

---

### IA-002-2 — Observability

Extend edge task telemetry (build on P0.1g-2 `EDGE_TASK_*`):

```text
EDGE_TASK_SUBMIT
EDGE_TASK_START
EDGE_TASK_END
EDGE_TASK_BLOCKED          (new)
```

Minimum fields:

```text
taskId
edgeKey
category
origin
queueWaitMs
occupancyMs
blockedByTask              (on BLOCKED / starvation correlate)
```

**Purpose:** answer **who holds the lane?** — not to change scheduling semantics in this package alone.

Execution RCA when admission fails:

```text
EDGE_EXECUTOR_STARVATION
blockedByTask=<taskId>
blockedByOrigin=<origin>
blockedByCategory=<category>
```

Media fact remains independent:

```text
MEDIA_EDGE_FAILED
reason=SRD_TIMEOUT
```

---

### IA-002-3 — Admission protection

**Authorized target:**

```text
MEDIA_CRITICAL obtains execution opportunity
```

**Not authorized to mandate:**

```text
priority queue
preemption API
task cancellation
JNI timeout on getStats
dedicated thread pool rewrite
```

Implementer **may** choose any mechanism that satisfies contract acceptance, for example:

```text
observation does not enter same lane
observation bounded / dropped under critical pressure
dedicated observation path
```

**Contract layer validates only:**

```text
SRD_APPLY START within realization window
when observation would previously poison lane
```

Reproduce scenario: session `52c8f036-…` class — `refreshAudioLevel` START without END, `SRD_APPLY` SUBMIT with no START.

---

## Explicitly not authorized

```text
executor rewrite / new executor framework
WebRTC getStats native fix
SRD timeout budget change
SRD retry / answer retry
ICE restart policy change
Recovery
Ready
Topology / Anchor
membership / completion predicate
UI / banner workaround
WiFi recovery main chain
```

---

## Implementation acceptance (desk / field)

### Acceptance IA-002-A — classification

All four first-batch workloads log `category=` on `EDGE_TASK_SUBMIT`.

`refreshAudioLevel` must **never** appear as uncategorized `OTHER`.

---

### Acceptance IA-002-B — admission under observation pressure

**Setup:** M01 anchor · M01→M04 edge · observation path active (same topology as P0.1g-2 failure class).

**Must observe when `SRD_APPLY` is submitted:**

```text
EDGE_TASK_SUBMIT  category=MEDIA_CRITICAL  taskType=SRD_APPLY
EDGE_TASK_START   category=MEDIA_CRITICAL  (within realization window)
```

**Must NOT observe:**

```text
SRD_APPLY SUBMIT with zero START until SRD_TIMEOUT
while sole lane holder is OBSERVATION with no END
```

Pass does **not** require M03 SRD native fix.

---

### Acceptance IA-002-C — attribution dimensions

On admission failure (if injected):

```text
Execution RCA:  EDGE_EXECUTOR_STARVATION + blockedBy*
Media fact:     MEDIA_EDGE_FAILED / SRD_TIMEOUT (if watchdog fires)
```

Do not collapse RCA into `SRD_TIMEOUT` alone.

---

## P0.1g relationship

```text
P0.1g Acceptance 1     HOLD (not re-run for contract wording)
P0.1g-C                re-validate after IA-002 impl PASS
P0.1g-A / P0.1g-B      CLOSED — do not reopen
```

P0.1g-C pass criterion (unchanged question):

> Does one edge's observation / lower-category lane hold deny **other edges'** or **same edge's** `MEDIA_CRITICAL` admission?

After IA-002-A/B/C pass on M04 starvation class → route to P0.1g field re-validation.

---

## Stop conditions

| Observation | Action |
|-------------|--------|
| Impl adds priority queue as **required** design in docs | Stop — violates IA-002 bounded scope |
| Impl changes SRD timeout | Stop — out of scope |
| Impl touches Recovery / Ready / Topology | Stop — re-domain |
| `SRD_APPLY START` under observation poison | **IA-002 PASS** → proceed P0.1g-C re-validation |
| `SRD_APPLY` still never START, same starvation signature | **IA-002 FAIL** — do not expand to option B without new decision |

---

## Evidence capture (suggested)

```text
talkback/logs/ia002-admission-<YYYYMMDD-HHMMSS>/
```

Minimum: M01 log with `EDGE_TASK_*` + `category` + M04 edge; session id; adjudication file.

---

## State diagram

```text
P0.1g-2 field RCA
        |
        v
Contract v0.1 ACCEPTED (FROZEN)
        |
        v
IA-002 AUTHORIZED (option A)  ← this document
        |
        +-- IA-002-1 classification
        +-- IA-002-2 observability
        +-- IA-002-3 admission protection
        |
        v
IA-002 acceptance (desk/field)
        |
        v
P0.1g-C re-validation
        |
        v
P0.1g Acceptance 1 (resume)
```

---

## Posture

```text
WiFi recovery main chain:     CLOSED — do not attach
Contract v0.1:                FROZEN — no wording churn
RCA:                          FROZEN — edge executor starvation
IA-002:                       OPENED — minimal admission fix only
Option B refactor:            EXCLUDED
```

Principle retained from P0.1g:

```text
先证明边界 → 再最小修复 → 不扩大问题域
```
