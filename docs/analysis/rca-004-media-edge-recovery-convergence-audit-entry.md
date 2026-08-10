# RCA-004 — Media Edge Recovery Convergence Audit

**Status:** AUDIT COMPLETE — **Finding issued** (still no impl)  
**Date:** 2026-08-11  
**Name (frozen):** Media Edge Recovery Convergence Audit  
**Finding:** [rca-004-media-edge-recovery-convergence-finding.md](./rca-004-media-edge-recovery-convergence-finding.md)  
**Predecessor (CLOSED):** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
**Field seeds:**  
- [mesh-m02-media-unrecovered-20260811-064426.md](./mesh-m02-media-unrecovered-20260811-064426.md)  
- [rca-003-ic-uvcp-gray-adjudication-20260811-062506.md](./rca-003-ic-uvcp-gray-adjudication-20260811-062506.md) (M03→M02 exclusion)

## Freeze card

```text
RCA-004 Media Edge Recovery Convergence Audit

Scope:
  inbound media-edge recovery convergence

Mode:
  audit only

No:
  implementation
  ownership redesign
  retry / ICE timeout / Phase-2
  UVCP / pill / residency clear
  Edge-scoped ownership design (until Finding proves shared cause)
```

## Core question (only)

> peer 自身恢复或 outbound edge 已 CONNECTED 后，为什么其它 peer → 它的 inbound edge 无法获得有效 media-action / negotiation admission？

Informal image (not a diagnosis): **inbound media recovery black hole** (e.g. M02).

## Layer discipline (do not mix)

```text
WiFi flap
   → Recovery Layer
        ├─ Edge recovered → UI healthy (RCA-003 CLOSED — projection)
        └─ Edge not recovered → UI degraded correct
              → THIS TRACK (media-edge admission / ownership lifecycle)
```

| Problem | Status | Layer |
|---------|--------|-------|
| Recovered + UI degraded | CLOSED | Presentation (RCA-003) |
| Unrecovered + UI degraded | OPEN | Media edge convergence (RCA-004) |

```text
degraded ≠ bug
Ask: why inbound edge lacks valid admission?
Not: why is the pill red?
```

## Established facts (field)

### Outbound OK

```text
M02 → M01 / M03  CONNECTED
```

WiFi / discovery / signaling / WebRTC baseline **not** the framing.

### M01 → M02 (inbound)

```text
HOST_RESTART
  → NEGOTIATION_NON_OWNER_BLOCKED
  → attempt_timeout / FAILED_MEDIA
  → ice=FAILED (symptom)
```

**Say:** admission predicate rejected the current negotiation actor.  
**Do not say yet:** owner scope must be wrong.

### M03 → M02 (inbound)

```text
NO_MEDIA_ACTION_OWNER
  → ice=CHECKING (symptom)
```

**Keep open (do not merge early):**

```text
A) no recovery action created
B) owner creation failed / not propagated
```

Same **symptom class** (inbound not converged); **cause not proven identical**.

## Audit questions (A1–A3 only)

### A1 — Ownership Binding

> media-action owner 当前绑定在哪个实体？

Output checklist (fact only — no judgment):

```text
Owner Scope:
[ ] Conference
[ ] Session
[ ] Endpoint
[ ] Edge
[ ] Attempt
```

Also record bind sites / keys (e.g. `sessionId` + `remoteModuleId` on `EdgeRecoveryRecord`).

**Code entry (read):**

- `ConferenceEdgeRecoveryController.assignMediaActionOwner(...)`  
- logs: `RECOVERY_MEDIA_OWNER_ASSIGNED` / `RECOVERY_MEDIA_ACTION_ASSIGNMENT`

### A2 — Obligation Granularity

> recovery obligation 按什么粒度存在？

Distinguish:

```text
Episode scope            = ?
Delivery obligation      = ?
Media-action obligation  = ?
```

Contrast architectures (observe which exists — do not redesign):

```text
RecoveryEpisode(peer)
  ├─ edge A
  └─ edge B

vs

one episode / shared obligation across edges
```

**Code entry (read):** `EdgeRecoveryRecord` / obligation generation / per-`ConferenceEdgeKey` maps.

### A3 — Admission Predicate (critical)

#### Case M01 — `NEGOTIATION_NON_OWNER_BLOCKED`

Why does `HOST_RESTART` / local host fail admission?

Find the reject/defer predicate (field already shows pattern around ICE restart dispatch):

```text
negotiationOwner != localModuleId → NEGOTIATION_NON_OWNER_BLOCKED → return
```

Answer: **what that predicate means** for actor vs media-action owner vs negotiation owner.

**Code entry (read):** ICE restart dispatch path near `NEGOTIATION_NON_OWNER_BLOCKED` + `ensureCanonicalNegotiationOwner`.

#### Case M03 — `NO_MEDIA_ACTION_OWNER`

Why is there no owner at timeout — no assign, failed assign, or no fallback acquisition?

**Code entry (read):** watchdog / attempt timeout abort classification (`NO_MEDIA_ACTION_OWNER` vs `OWNER_BLOCKED` vs `attempt_timeout`).

## Forbidden hypothesis jump

```text
NO_MEDIA_ACTION_OWNER
    ✗→  Owner scope must become edge-scoped

NEGOTIATION_NON_OWNER_BLOCKED
    ✗→  redesign ownership

ice=FAILED / CHECKING
    ✗→  ICE timeout / retry patch
```

Allowed:

```text
symptom class: inbound edge lacks valid media-action / negotiation admission
unknown cause: lifetime | scope | obligation grain | admission rule
```

## Expected output (not a fix)

One artifact: **Media Edge Recovery Convergence Finding**

```text
Finding-1:
  M01→M02 blocked by ______

Finding-2:
  M03→M02 missing ______

Common invariant:
  Inbound edge lacks valid media-action / negotiation admission
  (yes / no / unknown)

Unknown:
  Whether cause is:
    owner lifetime
    owner scope
    obligation scope
    admission rule
```

Then:

```text
common invariant?  no → split bugs
                   yes → only then design (P2)
```

## Priority

```text
RCA-003 CLOSE (done)
    → RCA-004 A1–A3 audit
    → shared invariant?
         ├─ no  → split
         └─ yes → design later
```

## Next action

```text
A1–A3 done → Finding issued
P2 design ONLY if prioritized (admission / dual-owner seam)
Not: impl · retry · UVCP · edge-scoped redesign jump
```

