# RCA-003-R5 — Failed Media Residency Clear Contract (SEALED)

**Status:** **R5.1–R5.3 ACCEPTED** · Implementation Candidate **NOT AUTHORIZED**  
**Date:** 2026-08-11  
**Kind:** Residency Clear Design (semantic freeze only)  
**Upstream:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md)  
**Parents:** [ADR-0045](../adr/0045-post-obligation-failed-media-residency-clear-admission.md) · [ADR-0044](../adr/0044-user-visible-connectivity-semantics-media-residency.md) · [ADR-0030](../adr/0030-presence-projection-contract.md)

## Freeze banner

```text
RCA-003 R4 COMPLETE
RCA-003 R5.1–R5.3 ACCEPTED (semantics)

Next (optional): Implementation Candidate — separate auth
Not yet: code · UI · WiFi · ICE · retry · more field soak
```

---

## R5.1 ACCEPT — FAILED_MEDIA = incident residency

```text
FAILED_MEDIA_RECOVERY
    = recovery incident residue
    = “entered failed recovery path; not yet confirmed cleared”

NOT:
    = current media unavailable
```

**Normative one-liner:**

```text
FAILED_MEDIA_RECOVERY means an uncleared recovery-incident residency,
not a live assertion that the peer media path is unusable right now.
```

**Why:** Field shows `MEDIA_LIFECYCLE=CONNECTED` (and ice/receivePath live) while residency still holds → treating it as current availability creates CONNECTED→degraded contradiction.

---

## R5.2 ACCEPT — clear predicate (no EDGE_RECOVERED)

```text
CLEAR_ALLOWED =
    obligationClosed
    AND
    mediaEvidenceHealthy

mediaEvidenceHealthy =
    iceConnected
    AND
    receivePathLive
```

```text
EDGE_RECOVERED is NOT required for residency clear.
EDGE_RECOVERED = completion truth.
Residency clear = “should this incident still project?”
```

Aligns with ADR-0045 GATE+E4 shape; **does not** add completion as a projection gate (avoids Completion→Projection stuck).

**Early clear while obligation OPEN?**

```text
NO — obligation must be closed before CLEAR_ALLOWED.
```

(Matches ADR-0045 post-obligation admission; R5 does not authorize open-obligation clear.)

---

## R5.3 ACCEPT — FAILED_MEDIA ≠ CURRENT_UNAVAILABLE

```text
FAILED_MEDIA          ≠  CURRENT_UNAVAILABLE
```

| Fact | Consumers |
|------|-----------|
| **FAILED_MEDIA / incident residency** | Recovery diagnostics, incident reporting, clear policy |
| **CURRENT_UNAVAILABLE** (live health) | User projection (`UserVisibleConnectivityProjection` / pill) |

**Forbidden (post-R5 intent):**

```text
if (failedMediaResidency) show degraded   // as sole current-health input
```

**Three-layer model (do not merge):**

```text
Media transport truth
    ICE_CONNECTED
    RECEIVE_PATH_LIVE

Recovery incident truth
    FAILED_MEDIA_RESIDENCY
        |  CLEAR_ALLOWED (R5.2)
        v
    CLEARED

User projection
    degraded / syncing / healthy
    ← current health only (not incident residue)
```

```text
ICE_CONNECTED does not auto-clear residency.
Residency waits CLEAR_ALLOWED, then projection may recover.
```

---

## ADR impact (named, not amended here)

| Artifact | Impact |
|----------|--------|
| ADR-0030 `mediaUnavailable(P) ⇔ failed-media residency` | **Tension** with R5.3 — needs amendment / successor ADR before IC changes UVCP inputs |
| ADR-0045 clear admission | Predicate shape **confirmed**; EDGE_RECOVERED **not** added |
| ADR-0044 presentation | Continues to map **current** unavailable → DEGRADED; must not read incident as current once decoupled |
| ADR-0038 / markRecovered | **Orthogonal** — not used as clear gate |

R5 seals **intent**. Predicate/decoupling **code** requires Implementation Candidate + ADR path — not this memo alone.

---

## Exit: R5 complete when

```text
[x] R5.1 ACCEPTED — incident residency
[x] R5.2 ACCEPTED — clear = closed + mediaEvidenceHealthy; no EDGE_RECOVERED
[x] R5.3 ACCEPTED — FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
[ ] Implementation Candidate opened (separate)
```

## Explicit non-goals (still)

```text
UI string patches
WiFi recovery / ICE / delivery / ownership reopen
Extra timeouts / retries
UVCP-hide while still treating residency as current unavailable without ADR change
```
