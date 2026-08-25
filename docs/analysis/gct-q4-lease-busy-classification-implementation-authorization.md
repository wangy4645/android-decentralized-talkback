# GCT Q4 × Lease Busy Classification — Implementation Authorization

**ID:** gct-q4-lease-busy-classification-ia  
**Date:** 2026-08-25  
**Version:** v0.1  
**Type:** Implementation Boundary / Authorization Record  
**Status:** **AUTHORIZED** (v0.1)  
**Implementation authorization:** **classification / log splice only**

**Does not amend** ADR-0056 · Failure Domain L3/L4 · Lease occupancy rules · A0.7 · Factory partition.

**Prerequisite (all frozen / accepted):**

| Gate | Status | Artifact |
|------|--------|----------|
| GCT Q4 splice | **ACCEPT WITH CLARIFICATION** | [group-conference-transition-contract-review.md](../architecture/group-conference-transition-contract-review.md) §5 · §7 Q4 |
| Lease terminal semantics | **APPROVED / FROZEN** | [domain-lease-terminal-semantics-contract.md](./domain-lease-terminal-semantics-contract.md) |
| Edge → Domain propagation | **FROZEN** | [edge-native-domain-failure-propagation-contract.md](./edge-native-domain-failure-propagation-contract.md) |
| M03 SRD RCA | **EVIDENCE FROZEN** | [m03-srd-timeout-host-pc-hang-rca-20260824.md](./m03-srd-timeout-host-pc-hang-rca-20260824.md) |
| Execution boundary | **FROZEN** | [coordinator-media-execution-boundary.md](../architecture/coordinator-media-execution-boundary.md) |

```text
GCT Q4 splice
        ↓
Lease Terminal Semantics
        ↓
This authorization (classification only)   ← gate
        ↓
Minimal PR
```

---

## Authorization statement

> **Authorize one minimal PR** so that **peer-held native-domain lease denial** is recorded and classified as Failure Domain **`DOMAIN_BLOCKED` (L2)**, and is **never** GCT **`PENDING`**, never **L1 `EDGE_LOCAL_FAILURE`**, and never a reason to vacate the lease.

**No authorization for:** force-release · `LEASE_NONE` on timeout · PC hard reset · native reset · Factory partition · SRD timeout budget · retry · markStuck/quarantine policy that clears occupancy · GCT START/ACCEPT engine-continuation (already authorized separately).

---

## Goal (narrow)

Close the Q4 splice in **runtime wording**, matching already-correct CFC-1 Scenario D classification:

```text
own lifecycle incomplete     → GCT PENDING     (unchanged; out of this PR)
peer lease RETAINED/UNKNOWN  → DOMAIN_BLOCKED
PENDING ≠ wait for peer JNI
DOMAIN_BLOCKED ≠ Conference unavailable
EDGE_FAILED ≠ LEASE_NONE
```

Field defect (session `e55028df-…`, M02):

```text
CONFERENCE_MEDIA_EDGE_FAILED
    reason=EDGE_LOCAL_FAILURE
    detail=NATIVE_DOMAIN_BUSY holder=…|M03
```

Classifier already emitted `DOMAIN_BLOCKED` / `scenario=D`. The **log reason** still names L1. That is the authorized fix.

**Not the goal:**

```text
Stop M03 JNI hang
Release or steal M03 lease
Make M02 proceed to SRD
Change requestEngine PENDING / READY / FAILED
Change SRD watchdog timeout
```

---

## Scope IN

### AUTH-Q4-1 — Impact-edge log reason

When SRD admission returns `LEASE_BUSY` (known holder) or equivalent peer-occupancy deny:

```text
MUST log a DOMAIN_BLOCKED-class reason (e.g. reason=DOMAIN_BLOCKED)
MUST keep holder / runtimeDomainRef / cause edge in the same line or existing NATIVE_DOMAIN_LEASE_BUSY + CFC-1 chain
MUST NOT use reason=EDGE_LOCAL_FAILURE for this path
```

`finishConferenceSrdDomainRejected` (or successor) is the expected touchpoint. Quarantine-without-holder remains a domain deny, **not** L1 self-failure; use a domain-class reason, not `EDGE_LOCAL_FAILURE`.

### AUTH-Q4-2 — GCT PENDING isolation (lock tests)

```text
LEASE_BUSY / DOMAIN_BLOCKED
    MUST NOT enqueue or resume GCT engine PENDING
    MUST NOT wait for holder NATIVE_IDLE / LEASE_NONE
```

If current code already does not do this, **tests that prove it** are in scope; no new pending queue.

### AUTH-Q4-3 — Lease occupancy freeze (negative tests)

```text
SRD_TIMEOUT / EDGE_FAILED on cause edge
    MUST NOT call releaseLease / assert LEASE_NONE
    MUST NOT GRANTED a different edge because the cause terminalized
```

Lock existing `ConferenceNativeExecutionDomain` behavior (`releaseLease` only from `ACTIVE` after native `block()` returns). **Do not** add force-release. **Do not** add timeout-path `releaseLease`.

---

## Scope OUT

```text
ConferenceNativeExecutionDomain slot-emptying policy
runWithLease finally / NORMAL release on happy-path EXIT (keep)
markStuck / quarantine / clearQuarantine behavior change
applyRemoteAnswer / JNI / Factory
GCT requestEngine continuation
UI copy beyond existing VISIBLE_DOMAIN_BLOCKED
L3 / L4 / Conference unavailable
WiFi recovery / membership / RNA-5/6
```

---

## Allowed files (expected)

```text
TalkbackCoordinator.kt          — finishConferenceSrdDomainRejected log reason only
tests adjacent to SRD domain admission / failure wiring
```

Coordinator behavior besides the log reason string is **not** in scope unless a test proves GCT PENDING is currently coupled (then AUTH-Q4-2 may remove that coupling only).

---

## Tests (required)

```text
LEASE_BUSY path log contains reason=DOMAIN_BLOCKED (or agreed domain-class token)
LEASE_BUSY path log does not contain reason=EDGE_LOCAL_FAILURE
onLeaseBusy still produces CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D
SRD_TIMEOUT does not releaseLease (existing domain tests remain green; add if missing)
```

---

## Field

**Not required** for this PR. RCA session `e55028df-…` remains the evidence that classification was already D and the log was wrong.

Optional smoke: same GROUP→CONFERENCE accept; M02 deny line uses `DOMAIN_BLOCKED`; M03 still `SRD_TIMEOUT`; lease still held (M02 still `LEASE_BUSY` if M03 hung).

---

## FAIL (any)

```text
releaseLease / LEASE_NONE as part of SRD_TIMEOUT
GCT PENDING used to wait for peer JNI
reason=EDGE_LOCAL_FAILURE retained on LEASE_BUSY path
FORCE_RELEASE / PC reset / Factory change
Conference marked unavailable solely because DOMAIN_BLOCKED
```
