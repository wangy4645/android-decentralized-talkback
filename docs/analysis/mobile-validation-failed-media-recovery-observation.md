# Mobile Validation — FAILED_MEDIA_RECOVERY Observation

**Status:** **SUPERSEDED by track close** · observation retained as episode baseline · **no runtime authorization**  
**Date:** 2026-08-08 · **Closed:** 2026-08-09  
**Episode:** `logs/mobile-validation-20260808-205929/` · session `9b36c96d-69c3-4f1d-91e9-c6296a49da3b`  
**Parents:** [rna-intent-observation-close.md](./rna-intent-observation-close.md) · [adr0043-checkpoint-close.md](./adr0043-checkpoint-close.md)  
**Close:** [mobile-validation-track-close.md](./mobile-validation-track-close.md) · Q1–Q3 desk complete

---

## Status board (mobile validation track)

```text
WiFi Recovery Architecture          CLOSED ✅
Mobile Validation                   CLOSED OBSERVATION ✅
  P1a                               CLOSED (Case A PASS)
  FAILED_MEDIA desk Q1–Q3           COMPLETE
  Future: Presentation Semantics ADR (independent; NOT STARTED)

See: mobile-validation-track-close.md
```

---

## 1. Episode

| Field | Value |
| ----- | ----- |
| Devices | M02 (host) · M03 (DUT, WiFi flap) · M01 (peer) |
| Trigger | Case A — M03 WiFi OFF ~10–15s → ON |
| APK | `main @ c6dc589` · `versionName=1.0.0` |
| Stimulus time | ~`21:02:14` (M03 ICE disconnect cascade) |

**Observed end-state (~`21:04:35`, log capture end):**

```text
M02 → M03 edge:
  iceConnectionState=CONNECTED
  edgeRecoveryPhase=FAILED_MEDIA_RECOVERY
  obligationOpen=false
  mediaUnavailable=true
  finalPresence=RECONNECTING

M03 → M02 edge:
  (same pattern)
```

**User-visible symptoms:**

- M02 UI: M03 shows reconnecting
- M03 UI: M02 shows reconnecting
- M01/M02 (no WiFi flap): Poor Network banner (separate finding — P1a wiring gap)

---

## 2. Ownership trace (partial — desk in progress)

| Fact | Producer (known / suspected) | Consumer (known / suspected) |
| ---- | ---------------------------- | --------------------------- |
| `mediaUnavailable` | `ConferenceEdgeRecoveryController.isMediaUnavailable()` — ADR-0030 failed-media residency | `TalkbackCoordinator.conferenceMediaUnavailable()` → UVCP / rprobe |
| `FAILED_MEDIA_RECOVERY` | `ConferenceEdgeRecoveryController` — `record.phase = FAILED_MEDIA_RECOVERY` | Runtime projection · rprobe `edgeRecoveryPhase` |
| `obligationOpen` | `ConferenceEdgeRecoveryController` obligation lifecycle | Barrier · completion · rprobe |
| `finalPresence=RECONNECTING` | `MeetingPresenceDisplay` / `UserVisibleConnectivityProjection` | Meeting avatar row · connecting hints |

**Not yet confirmed:** clear/reset path for `mediaUnavailable` after ICE reconnect in this episode.

---

## 3. Questions (fixed)

### Q1 — Who owns `mediaUnavailable` lifecycle?

```text
Who sets mediaUnavailable=true?
Who clears it?
Under what conditions?
```

**Initial code pointers:**

- `ConferenceEdgeRecoveryController.isMediaUnavailable()`
- `ConferenceEdgeRecoveryController` → `FAILED_MEDIA_RECOVERY` phase writer (~line 2008)
- Tests: `ConferenceEdgeRecoveryControllerTest` — failed-media residency, route-restore reevaluate

### Q2 — Does `FAILED_MEDIA_RECOVERY` have terminal semantics?

```text
Is there a terminal event / phase after FAILED_MEDIA_RECOVERY?
Or does residency persist until explicit reevaluate / new attempt?
```

**Hypothesis A:** terminal exists; presentation ignores it → mapping bug  
**Hypothesis B:** no terminal; residency is intentional → state semantics gap

**Desk must not pre-judge.**

### Q3 — Is `RECONNECTING` a valid projection for this state?

```text
At ICE=CONNECTED + mediaUnavailable=true + obligationOpen=false:
  should UI show RECONNECTING?
  or a distinct terminal/degraded state?
```

**Constraint:** `ICE CONNECTED ≠ media path healthy` — do not map to `ONLINE` without evidence.

---

## 4. Explicit non-goals

```text
No:
  ADR-0043 reopen
  RNA reopen
  completion predicate change
  ICE policy change
  recovery budget change
  WiFi flap matrix expansion (until Q1–Q3 answered)
  mapping FAILED_MEDIA_RECOVERY → ONLINE
```

---

## 5. Related finding (separate track)

**P1a presentation wiring gap** — `ConferenceNetworkBannerProjection` implemented but not wired into meeting presentation. See presentation dependency inventory before PR.

**Presentation dependency inventory (2026-08-08):**

| Component | Uses legacy `ConferenceNetworkIndicatorProjector`? | Uses `ConferenceNetworkBannerProjection`? |
| --------- | --------------------------------------------------- | ----------------------------------------- |
| `MeetingFragment` | ✅ (`networkLabel == "Poor"`) | ❌ |
| `TalkViewModel` | ✅ (`conferenceNetworkIndicator`, `poorNetwork`) | ❌ |
| `ConferenceDisplayStateResolver` | ✅ (via `TalkUiState.networkLabel`) | ❌ |
| `NetworkStatusHelper` | ✅ | ❌ |
| `TalkbackRuntimeManager.channelMeetingQos` | ✅ (aggregate) | ❌ |
| `ConferenceNetworkBannerProjection` | — | ✅ (tests only) |

**PR intent:** migrate meeting presentation to `ConferenceNetworkBannerProjection`, not a one-line banner fix.

---

## 6. One-line statement

> Mobile validation episode 1: architecture boundaries held; product layer exposed P1a wiring gap (confirmed) and FAILED_MEDIA_RECOVERY → RECONNECTING projection anomaly (observed, Q1–Q3 pending).
