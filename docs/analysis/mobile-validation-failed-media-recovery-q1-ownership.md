# FAILED_MEDIA_RECOVERY — Q1: mediaUnavailable Lifecycle Ownership

**Status:** **DRAFT** · **desk analysis only** · **no runtime authorization**  
**Date:** 2026-08-08  
**Track:** FAILED_MEDIA_RECOVERY semantics (not WiFi recovery investigation)  
**Parents:** [mobile-validation-failed-media-recovery-observation.md](./mobile-validation-failed-media-recovery-observation.md) · [mobile-validation-case-a-p1a-verdict.md](./mobile-validation-case-a-p1a-verdict.md)

---

## Scope

Q1 only. Does **not** adjudicate UI semantics (Q2) or enum overload (Q3).

**Episode evidence:** `logs/mobile-validation-case-a-p1a-20260808-215605/` · session `418c8324-d375-4008-ba3f-6e8e0d057ac1`

---

## Q1: mediaUnavailable lifecycle ownership

### 1. Writer — who sets `mediaUnavailable = true`?

**UVCP consumer path (UI):**

```text
TalkViewModel.edgeMediaUnavailablePeer()
  → TalkbackCoordinator.conferenceMediaUnavailable()
  → MediaUsabilityFact.isUnavailable(mediaState, failedMediaResidency)
```

**Evidence:**

| Layer | File | Mechanism |
| ----- | ---- | --------- |
| UI read | `talkback-app/.../TalkViewModel.kt` | `runtime.conferenceMediaUnavailable(sessionId, moduleId)` |
| Coordinator | `android-board-talkback/.../TalkbackCoordinator.kt` | `MediaUsabilityFact.isUnavailable(...)` |
| Fact composition | `android-board-talkback/.../MediaUsabilityFact.kt` | `failedMediaResidency \|\| mediaState in {RECONNECTING, FAILED}` |
| Residency bit | `android-board-talkback/.../ConferenceEdgeRecoveryController.kt` | `isMediaUnavailable()` ⇔ `record.phase.isFailedMediaRecovery()` |
| Residency enter | same | `enterFailedMediaResidency()` → `phase = FAILED_MEDIA_RECOVERY` |

**ADR-0030 contract (code comment):**

```text
failed-media residency (e.g. FAILED_MEDIA_RECOVERY) == mediaUnavailable(P)
```

**Writers that enter residency (`mediaUnavailable` becomes true):**

| Writer | Trigger | Log marker |
| ------ | ------- | ------------ |
| `ConferenceEdgeRecoveryController.enterFailedMediaResidency()` | attempt timeout, ice_restart_failed, etc. | `FAILED_MEDIA_RECOVERY session=... remote=...` |
| `MediaUsabilityFact` (secondary) | `MediaState.RECONNECTING` / `FAILED` without residency | participant media axis |

**Field evidence (M02 → M03 edge):**

```text
21:59:24  FAILED_MEDIA_RECOVERY remote=M03 reason=attempt_timeout
          failureClass=MEMBERSHIP_CONVERGENCE_TIMEOUT
21:59:24  edgePhase=FAILED_MEDIA_RECOVERY iceConnected=true mediaReady=true
```

---

### 2. Clear owner — who sets `mediaUnavailable = false`?

`mediaUnavailable` clears when **both** are false:

1. `failedMediaResidency` — edge phase leaves `isFailedMediaRecovery()`
2. `mediaState` — not `RECONNECTING` / `FAILED`

**Explicit clear paths for failed-media residency:**

| Clear path | Owner | Phase transition | Log marker |
| ---------- | ----- | ---------------- | ---------- |
| Edge recovered | `RecoveryCompletionPolicy.markRecovered()` | `FAILED_MEDIA_RECOVERY` → `RECOVERED` | `RECOVERY_EDGE_RECOVERED` |
| Supersede / successor | `ConferenceEdgeRecoveryController.supersedeFailedResidencyAndAdmit()` | failed residency → new attempt | `decision=SUPERSEDED` |
| Debounce-only flap | `clearDebouncingSuspicion()` | → `CONNECTED` (no prior failed residency) | `RECOVERY_DEBOUNCE_CLEARED` |

**Not a clear path for residency:**

| Event | Effect on `mediaUnavailable` |
| ----- | ---------------------------- |
| `RecoveryCompletionPolicy.closeObligation(OBLIGATION_DEADLINE)` | Closes obligation only; **phase stays `FAILED_MEDIA_RECOVERY`** |
| `onIceConnected` while in `FAILED_MEDIA_RECOVERY` | Early return; **does not clear residency** (`ConferenceEdgeRecoveryController` ~L2672) |
| ICE reconnect alone | Does not clear `FAILED_MEDIA_RECOVERY` residency (test: `does not clear FAILED_MEDIA_RECOVERY residency`) |

**`mediaRestored` fact (separate from residency):**

| Set | `noteMediaRestored()` / reattach-accepted path |
| Clear | `clearMediaRestoredFact()` on ICE DISCONNECTED/FAILED |

**Field evidence — obligation closed but residency persists:**

```text
21:59:54  RECOVERY_OBLIGATION_CLOSE_REQUESTED remote=M03
          reason=OBLIGATION_DEADLINE phase=FAILED_MEDIA_RECOVERY
          mediaRestored=true iceConnected=true
21:59:54  RECOVERY_OBLIGATION_CLOSED remote=M03 reason=OBLIGATION_DEADLINE

~22:01    REACHABILITY_PROBE module=M03
          edgeRecoveryPhase=FAILED_MEDIA_RECOVERY
          mediaUnavailable=true obligationOpen=false
          finalPresence=RECONNECTING
```

---

### 3. Post ICE CONNECTED behavior

**Question:** Can `ICE_CONNECTED + mediaUnavailable=true` legally coexist?

**Verdict: YES** (by current contract)

```text
ICE transport restored
        +
edge phase still FAILED_MEDIA_RECOVERY (ADR-0030 residency)
        =
mediaUnavailable=true
```

Obligation state is orthogonal:

```text
obligationOpen=true   → during observation window after failed-media entry
obligationOpen=false  → after OBLIGATION_DEADLINE close (residency may remain)
```

Both combinations observed in field.

**Important:** `mediaRestored=true` and `mediaUnavailable=true` can coexist in the same probe — they are different facts (`mediaRestored` attempt fact vs ADR-0030 residency bit).

---

### 4. Classification (A / B / C / D)

| Option | Assessment |
| ------ | ---------- |
| **A. missing clear owner** | **NO** — clear owners documented above |
| **B. expected failed-media terminal** | **PRIMARY** — residency retained after failed attempt; obligation deadline does not clear residency |
| **C. stale projection** | **NOT Q1** — UVCP reads live coordinator facts; projection follows residency. Any UI mismatch deferred to Q2/Q3 |
| **D. insufficient evidence** | **NO** — field + code chain corroborated |

**Q1 classification: B (expected failed-media terminal residency per ADR-0030)**

---

## A / B / C hypothesis split (for Q2 handoff)

| Hypothesis | Q1 result |
| ---------- | ----------- |
| **A — transport ok, media failure terminal** | **Supported** on M02↔M03 / M03↔M02 edges |
| **B — media recovered, flag never cleared** | **Not supported** — `mediaRestored=true` present; residency intentionally retained |
| **C — projection reads wrong source** | **Not Q1** — source is `MediaUsabilityFact` + `isMediaUnavailable()`; consistent with residency |

---

## Observer asymmetry (note only — not Q1 verdict)

Same stimulus, different local edges:

| Observer | Remote | Edge phase (tail) | `finalPresence` |
| -------- | ------ | ----------------- | --------------- |
| M01 | M03 | `RECOVERY_PENDING` | `SYNCING` |
| M02 | M03 | `FAILED_MEDIA_RECOVERY` | `RECONNECTING` |
| M03 | M02 | `FAILED_MEDIA_RECOVERY` | `RECONNECTING` |

Per-edge recovery state is local to `(observer, remote)` — not a single global peer fact. Q2 must not collapse these into one narrative.

---

## Non-goals (this document)

```text
✗ UI semantics (Q2)
✗ RECONNECTING/SYNCING enum redesign (Q3)
✗ recovery timeout / completion predicate changes
✗ code changes
✗ ADR proposal
```

---

## Next step

**Q2:** Classify `FAILED_MEDIA_RECOVERY` nature (terminal vs intermediate vs diagnostic) and whether `RECONNECTING` presentation is correct for residency-after-deadline.
