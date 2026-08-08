# FAILED_MEDIA_RECOVERY — Q2: State Classification

**Status:** **DRAFT** · **desk analysis only** · **no runtime authorization**  
**Date:** 2026-08-09  
**Track:** FAILED_MEDIA_RECOVERY semantics (not WiFi recovery investigation)  
**Parents:** [mobile-validation-failed-media-recovery-q1-ownership.md](./mobile-validation-failed-media-recovery-q1-ownership.md)

---

## Scope

Q2 answers **what category** `FAILED_MEDIA_RECOVERY` is in the system model.

Does **not**:

```text
✗ ask how to fix it
✗ change RECONNECTING copy / enum
✗ auto-transition to ONLINE
✗ modify residency clear rules
✗ open ADR
✗ authorize runtime change
```

**Episode:** `logs/mobile-validation-case-a-p1a-20260808-215605/` · session `418c8324…`

---

## Q2-A: Terminal or intermediate?

### Terminal evidence (supported)

| Signal | Evidence |
| ------ | -------- |
| Code names it terminal | `EdgeRecoveryPhase.isFailedMediaRecovery()` comment: *"Terminal media-recovery failure retained for R24 Strategy A residency"* |
| Attempt terminal on entry | `enterFailedMediaResidency()`: *"attempt terminal, obligation stays OPEN"* |
| Not actively recovering | `isActivelyRecovering()` returns **false** for `FAILED_MEDIA_RECOVERY` |
| No automatic retry | Exit only via `markRecovered()` or `supersedeFailedResidencyAndAdmit()` (needs resurrection / permitted actions) |
| Field: no auto exit | After `21:59:24` entry → `21:59:54` deadline → `~22:01` still `edgeRecoveryPhase=FAILED_MEDIA_RECOVERY` |

### Intermediate evidence (not supported)

| Signal | Field / code |
| ------ | ------------ |
| Automatic background retry | **Absent** after residency entry on M02→M03 |
| Eventual RECOVERED without new episode | **Absent** (`RECOVERY_EDGE_RECOVERED` for M03 not observed post-failure) |
| Soft phase that always progresses | Contradicted by R24 residency retention |

**Q2-A verdict:** **terminal residency** (not an intermediate recovery phase).

```text
FAILED_MEDIA_RECOVERY
    = attempt-terminal failed-media residency
    ≠ active recovery phase
```

---

## Q2-B: Why retain after deadline?

### Observed timeline (M02 → M03)

```text
21:59:24  FAILED_MEDIA_RECOVERY (attempt_timeout / MEMBERSHIP_CONVERGENCE_TIMEOUT)
21:59:54  OBLIGATION_DEADLINE → obligationOpen=false
          phase remains FAILED_MEDIA_RECOVERY
~22:01    ICE_CONNECTED + mediaUnavailable=true + finalPresence=RECONNECTING
```

### Deadline semantics

| Interpretation | Fits? |
| -------------- | ----- |
| **A — "stop trying"** (close observation / stop ownership window) | **YES** |
| **B — "failure state completed"** → expect phase exit | **NO** — phase intentionally retained |

Contract evidence:

```text
closeObligation(OBLIGATION_DEADLINE)
  → stamps obligationClosedAtMs
  → does NOT change EdgeRecoveryPhase

isMediaUnavailable()
  → phase.isFailedMediaRecovery() only
  → independent of obligationOpen
```

`edgeObligationOpen()` returns false after deadline even while phase remains failed-media residency — obligation and residency are **orthogonal**.

**Q2-B verdict:** Deadline means **"stop trying"** (A), not "failure completed / clear residency" (B). Retention after deadline is **expected**.

Do **not** authorize:

```text
deadline → force ONLINE
deadline → clear mediaUnavailable
```

---

## Q2-C: Presentation mapping (observation only)

### Current chain

```text
FAILED_MEDIA_RECOVERY (residency)
        ↓
isMediaUnavailable() = true
        ↓
MediaUsabilityFact.isUnavailable(...) = true
        ↓
UVCP.deriveAxes(mediaUnavailable=true, recovering=false)
        ↓
  media  = MEDIA_UNAVAILABLE
  control = SYNCING   ← mediaUnavailable itself forces SYNCING
        ↓
UVCP.project → RECONNECTING
        ↓
EndpointStatus.RECONNECTING / UI "reconnect"
```

Also: `LocalReachability`: `recovering || mediaUnavailable` → presence `RECONNECTING`.

### Fact vs label mismatch (field)

```text
transport:   ICE CONNECTED
media fact:  mediaRestored=true (attempt fact)
residency:   FAILED_MEDIA_RECOVERY
obligation:  CLOSED
presentation: RECONNECTING
```

User-facing word **"reconnecting"** implies *in progress*.  
System state is closer to: *media path marked unavailable after failed attempt / degraded residency*.

### Important: runtime already distinguishes; UVCP collapses

`ConferenceRuntimeState` separates:

| Field | Meaning |
| ----- | ------- |
| `mediaRecovering` | active media repair |
| `conferenceDegraded` | recovering **or** FAILED_MEDIA residency |

UVCP / peer UI currently collapse both into **`RECONNECTING`**.

That is a **presentation semantics** observation for Q3 — not a lifecycle bug under Q2.

---

## Q2 classification verdict

```text
FAILED_MEDIA_RECOVERY:
    terminal residency

OBLIGATION_DEADLINE:
    "stop trying" — closes obligation only

RECONNECTING:
    overloaded presentation label for this residency
    (active reconnect vs terminal unavailable/degraded)

Classification:
    presentation semantics issue
    (not missing lifecycle transition)
```

Closest to **Result A** in the Q2 framework.

| Rejected | Why |
| -------- | --- |
| Result B (intermediate + missing terminal) | Code + field show terminal residency with intentional retention |
| Result C (diagnostic-only) | Residency drives UVCP / `mediaUnavailable` / user-visible peer state — not logs-only |

---

## Handoff to Q3 (not started)

Q3 may ask whether `RECONNECTING` is overloaded — **classification only**, no enum/UI change yet.

Candidates for later discussion (unauthorized):

```text
active repair     → RECONNECTING
failed residency  → DEGRADED / distinct label?
```

**Forbidden until authorized:** enum add, copy change, force ONLINE, residency clear rule change, ADR.

---

## Non-goals preserved

```text
✗ recovery timeout change
✗ completion predicate change
✗ ADR-0043 / RNA reopen
✗ WiFi matrix retest
```

---

## Status board

```text
Q1 Ownership                 COMPLETE ✅  (expected residency)
Q2 State Classification      COMPLETE ✅  (terminal + presentation overload)
Q3 Presentation Overload     PENDING ⏳
Runtime changes              NONE AUTHORIZED
```
