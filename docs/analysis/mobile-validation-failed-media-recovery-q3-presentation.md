# FAILED_MEDIA_RECOVERY — Q3: Presentation Semantics Overload

**Status:** **DRAFT** · **desk analysis only** · **no runtime authorization**  
**Date:** 2026-08-09  
**Track:** FAILED_MEDIA_RECOVERY semantics (not WiFi recovery investigation)  
**Parents:** [mobile-validation-failed-media-recovery-q2-classification.md](./mobile-validation-failed-media-recovery-q2-classification.md)

---

## Scope

Q3 answers only:

> Does current presentation compress multiple distinct user realities into `RECONNECTING`?

Does **not**:

```text
✗ rename RECONNECTING
✗ add FAILED_MEDIA enum
✗ change UVCP contract
✗ change recovery timeout
✗ auto clear residency
✗ open ADR
✗ design UI copy
```

---

## Model after Q1 / Q2 (frozen inputs)

```text
Transport          ICE CONNECTED                 ✅
Obligation         deadline → obligationOpen=false ✅
Media residency    FAILED_MEDIA_RECOVERY terminal ✅
Presentation       RECONNECTING                  ⚠️ under study
```

Underlying facts are consistent. Contradiction is at **user-semantic mapping**.

---

## Q3-A: What realities map to RECONNECTING?

### Declared contract (narrow)

```30:30:talkback-app/src/main/java/com/talkback/appprod/ui/TalkUiState.kt
    /** Conference peer media lost; repair/recovery active (ADR-0034). */
```

UI string: `status_reconnecting` = **"Reconnecting…"** (implies in-progress repair).

### Actual producers (wide)

| # | Reality | Mechanism → `RECONNECTING` |
| - | ------- | -------------------------- |
| 1 | **Active edge recovery** (`isRecoveringPeer` / `isActivelyRecovering`) | UVCP: `recovering=true` → control `SYNCING`; often + media unavailable → `RECONNECTING`. Also `ConferenceEndpointStatusMapper` forces RECONNECTING when `isRecoveringPeer`. |
| 2 | **Terminal failed-media residency** (`mediaUnavailable` / `FAILED_MEDIA_RECOVERY`) | UVCP: `mediaUnavailable=true` → media `MEDIA_UNAVAILABLE` **and** control forced `SYNCING` → `RECONNECTING`. `LocalReachability`: `mediaUnavailable` → presence `RECONNECTING`. |
| 3 | **Receive path lost after media ever live** | UVCP: `mediaEverLive && !receivePathLive` → control `SYNCING` + media unavailable → `RECONNECTING`. `LocalReachability` same pattern. |
| 4 | **Control sync pending** (with media unavailable) | UVCP: `controlSyncPending` contributes to `SYNCING` control; with `MEDIA_UNAVAILABLE` → `RECONNECTING`. |
| 5 | **Control degraded** (with media unavailable) | UVCP: `MEDIA_UNAVAILABLE` + `DEGRADED` → `RECONNECTING`. |
| 6 | **Display-state reconnect / failed** | `VISIBLE_RECONNECTING` / `VISIBLE_FAILED` → `EndpointStatus.RECONNECTING` (legacy mapper path). |
| 7 | **Connecting after prior connect** | `VISIBLE_CONNECTING` + `wasEverConnected` → `RECONNECTING` (not first join). |

### Critical collapse in UVCP.deriveAxes

```text
mediaUnavailable=true
        ↓
media  = MEDIA_UNAVAILABLE
control = SYNCING   (mediaUnavailable itself prevents STABLE)
        ↓
project → always RECONNECTING
```

`MEDIA_UNAVAILABLE + STABLE → DEGRADED` exists in `project()`, but **`deriveAxes` never emits STABLE while `mediaUnavailable=true`**.  
So terminal residency cannot surface as `DEGRADED` through the live adapter — only as `RECONNECTING`.

### Field corroboration (Case A episode)

| Observer | Internal facts | User label |
| -------- | -------------- | ---------- |
| M01 → M03 | `RECOVERY_PENDING`, `mediaUnavailable=false`, `finalPresence=SYNCING` | Sync (active / control path) |
| M02 → M03 | `FAILED_MEDIA_RECOVERY`, `obligationOpen=false`, ICE CONNECTED, `mediaUnavailable=true` | **Reconnecting…** |
| M03 → M02 | same terminal residency pattern | **Reconnecting…** |

Same meeting; different realities; M02/M03 terminal residency presented as in-progress reconnect.

**Q3-A verdict:** Yes — **same label, different realities**.

```text
RECONNECTING covers at least:
  - active repair
  - terminal media residency
  - receive-path loss after prior media
  - several control/display fallbacks
```

---

## Q3-B: Can the user distinguish?

### What UI surface retains

| Surface | Exposed | Lost? |
| ------- | ------- | ----- |
| `EndpointUiItem.status` | single `EndpointStatus` | yes — only one enum |
| `EndpointUiBinder` | string + dot for that status | yes |
| `TalkUiState` peer list | no `mediaUnavailable` / `mediaRecovering` fields | yes |
| Conference network banner (P1a) | conference-level scope only | N/A for peer label |
| REACHABILITY_PROBE logs | full bits | diagnostics only — not product UI |

### Internal facts available before collapse

Coordinator / UVCP inputs already distinguish:

```text
isRecoveringPeer     (active repair)
mediaUnavailablePeer (terminal residency / media axis)
receivePathLive
mediaEverLive
controlSyncPending / controlDegraded
```

Runtime also distinguishes `mediaRecovering` vs `conferenceDegraded` (`ConferenceRuntimeState`).

### After collapse

```text
UI sees only:
  EndpointStatus.RECONNECTING
  "Reconnecting…"
```

User cannot tell:

```text
"system is still trying"
        vs
"attempt ended; media still marked unavailable"
```

**Q3-B verdict:** Necessary information is **lost at the presentation boundary**. Not a missing internal state model — a **lossy public projection**.

---

## Q3-C: Classification exit

### Result 1 — SELECTED

```text
RECONNECTING
  = active recovery + terminal failure (+ other paths)

classification:
  presentation overload
```

### Rejected

| Result | Why rejected |
| ------ | ------------ |
| **Result 2** (accepted coarse abstraction) | Contract docstring claims *"repair/recovery active"*; terminal residency contradicts that claim. Not a clean intentional abstraction. |
| **Result 3** (state model extension required) | Internal facts already exist. Gap is projection/label compression, not absence of underlying state. Extension may be a *later UX ADR option*, not a Q3 necessity. |

---

## Desk chain summary (Q1–Q3)

| Q | Finding |
| - | ------- |
| Q1 | `mediaUnavailable` has clear writer/clear owners; residency after ICE CONNECTED is **expected** |
| Q2 | `FAILED_MEDIA_RECOVERY` = **terminal residency**; deadline = **stop trying** |
| Q3 | `RECONNECTING` = **overloaded presentation label**; user cannot distinguish active vs terminal |

```text
FAILED_MEDIA_RECOVERY lifecycle:   correct (for this desk)
Presentation:
  RECONNECTING label overloaded
```

---

## Authorized next discussion (not authorized work)

Possible future tracks (**none started**):

```text
A. Small presentation ADR / UX decision
   - whether terminal residency may use DEGRADED / distinct copy
   - without changing recovery FSM

B. Accept overload temporarily
   - document as known UX coarseness
   - no code change

C. State model extension ADR
   - only if product requires a new public enum
```

**Still forbidden without new authorization:**

```text
✗ rename / split RECONNECTING in code
✗ add FAILED_MEDIA enum
✗ UVCP contract change
✗ residency clear / force ONLINE
✗ recovery timeout / completion predicate
✗ reopen ADR-0043 / RNA
✗ WiFi recovery matrix
```

---

## Status board

```text
FAILED_MEDIA_RECOVERY

Q1 Ownership                 COMPLETE ✅
Q2 Classification            COMPLETE ✅
Q3 Presentation Semantics    COMPLETE ✅
  Result: presentation overload

Architecture changes         NONE AUTHORIZED
ADR-0043 / RNA               FROZEN
WiFi Recovery Architecture   CLOSED
```

---

## One-line verdict

> Lifecycle is coherent; the product-visible contradiction is that terminal failed-media residency is shown as **"Reconnecting…"**, collapsing active repair and finished-but-unhealthy media into one user label.
