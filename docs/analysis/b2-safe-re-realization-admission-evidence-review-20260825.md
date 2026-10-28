# B2 Safe Re-Realization — Admission Evidence Review

**ID:** b2-safe-re-realization-admission-evidence-review-20260825  
**Date:** 2026-08-25  
**Type:** Architecture Decision Review  
**Status:** **DECIDED (evidence model)** · B2 implementation **NOT AUTHORIZED**

**Prerequisites (frozen):**

| Gate | Artifact | Status |
|------|----------|--------|
| Primary defect | [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md) | FROZEN |
| Recovery architecture | [native-srd-recovery-decision-review-20260825.md](./native-srd-recovery-decision-review-20260825.md) | DECIDED · B selected |
| B1 control plane | [edge-srd-recovery-ia-v0.1-b1.md](./edge-srd-recovery-ia-v0.1-b1.md) | **FIELD PASS** |
| B1 field evidence | `talkback/logs/gct-b1-field-20260825-105501/` | PASS · terminal verified |

```text
B1 FIELD PASS (control plane)
        ↓
This review — Safe Admission Evidence Model   ← DECIDED here
        ↓
Domain-idle observation IA (read-only)        ← next authorized track (NOT STARTED)
        ↓
B2 IA (re-realization)                        ← NOT AUTHORIZED until idle evidence closed
        ↓
B2 implementation                             ← NOT AUTHORIZED
```

---

## Decision question

After **Shared Native SRD Fault** (JNI non-return on `shared-factory`), under **no force-release / no PC reset / no domain reset**:

> Can `shared-factory` ever become **provably safe to admit** again, and what **facts** must B2 require before edge re-realization?

This review does **not** design SRD retry mechanics. It defines **admission evidence** and **authorization gates**.

---

## Frozen shortcuts (non-negotiable)

These MUST NOT become B2 admission inputs:

```text
EDGE_FAILED              ≠  NATIVE_IDLE
RECOVERY_TERMINAL        ≠  LEASE_NONE
watchdog timeout         ≠  safe admission
SESSION_END              ≠  domain slot cleared
NATIVE_DOMAIN_LEASE_BUSY ≠  future grant will succeed
DOMAIN_BLOCKED lifted    ≠  native execution finished
```

B1 field explicitly proved the last two rows: recovery intent terminates on session end while domain contention facts persisted through hangup.

---

## B1 field — domain / lease fact inventory

**Session:** `7a78e333-f349-4735-ac31-39d91b0d38ae` · CH-01 · M01 host  
**Log:** `talkback/logs/gct-b1-field-20260825-105501/M01-talkback.log`

### Successful edge (M02 — first SRD)

```text
NATIVE_DOMAIN_LEASE_REQUEST   (M02)
NATIVE_DOMAIN_LEASE_GRANTED   (M02)
NATIVE_DOMAIN_EXECUTION_ENTER (M02)
SRD_NATIVE_CALL_ENTER … SRD_EXIT … SRD_NATIVE_CALL_EXIT
NATIVE_DOMAIN_EXECUTION_EXIT  (M02, elapsedMs=108)
```

Lease released via `ConferenceSrdNativeDomainAdmission.runWithLease` `finally` (normal path).

### Failure edge (M03 — second SRD hang)

```text
NATIVE_DOMAIN_LEASE_REQUEST   (M03)
NATIVE_DOMAIN_LEASE_GRANTED   (M03)
NATIVE_DOMAIN_EXECUTION_ENTER (M03)
… no SRD_EXIT / no SRD_NATIVE_CALL_EXIT / no NATIVE_DOMAIN_EXECUTION_EXIT …
SRD_WATCHDOG_GAP              holderEdge=M03 · missing=SRD_EXIT · state=POST_NATIVE_CALLBACK_WAIT
EDGE_FAILED · RECOVERY_REQUESTED · RECOVERY_WAITING_FOR_DOMAIN
```

Lease **not** released: `runWithLease` `finally` never runs while native `block()` is stuck.

### Impact edge (M04 — Scenario D)

```text
NATIVE_DOMAIN_LEASE_REQUEST (M04)
NATIVE_DOMAIN_LEASE_BUSY    holderEdgeKey=…|M03
DOMAIN_BLOCKED
(no RECOVERY_REQUESTED)
```

### Session end (M01 hangup, ~4 min after hang)

```text
SESSION_TERMINATED / LOCAL_HANGUP
RECOVERY_TERMINAL  terminalReason=SESSION_END  edge=M03
MEDIA_RELEASE_* on M02/M03/M04
(no NATIVE_DOMAIN_* events after hang)
(no NATIVE_DOMAIN_EXECUTION_EXIT for M03 — ever)
```

### B1 field conclusion for B2

```text
Hang path:
  lease granted → execution entered → native never returns → lease retained

Session end:
  recovery intent cleared
  domain lease / native execution truth NOT observed to idle

Self-heal without force-release:
  NOT OBSERVED in this run
  NOT DISPROVEN globally — but no positive idle evidence exists today
```

---

## §1 — Domain Idle: sufficient evidence model

**Definition:** *Native idle* means the process-wide `shared-factory` realization slot is **safe to grant** to a **new** edge execution without preempting unknown in-flight native work.

### Evidence tiers

| Tier | Name | Required observables | B2 admission |
|------|------|----------------------|--------------|
| **E0** | Unknown | Hang without `NATIVE_DOMAIN_EXECUTION_EXIT`; or holder present after watchdog | **DENY** |
| **E1** | Execution complete | `NATIVE_DOMAIN_EXECUTION_EXIT` for prior holder **same edgeKey attempt** | **INSUFFICIENT alone** (normal path only) |
| **E2** | Lease none | E1 + domain snapshot `holderEdgeKey = null` (no ACTIVE/STUCK/QUARANTINED holder) | **Necessary** · not yet **sufficient** |
| **E3** | Safe idle (proposed) | E2 + no `SRD_WATCHDOG_GAP` open on domain + no `POST_NATIVE_CALLBACK_WAIT` + admission gate read confirms `LeaseState` absent or policy-cleared | **Candidate sufficient** — requires observation IA |
| **E4** | Policy quarantine clear | Explicit quarantine lift fact (future) | TBD · not in scope |

### What is NOT sufficient today

```text
NATIVE_DOMAIN_EXECUTION_EXIT alone     — only proves normal completion path
RECOVERY_TERMINAL(SESSION_END)         — control-plane teardown only
MEDIA_RELEASE_COMPLETE                 — media layer ≠ native domain lease
SHARED_FACTORY_RELEASE_EXIT            — factory refcount ≠ realization lease
Coordinator still runnable             — unrelated to native safety
```

### Observability gap (blocks E2/E3 field proof)

Current native domain logs include:

```text
NATIVE_DOMAIN_LEASE_REQUEST | GRANTED | BUSY | QUARANTINED
NATIVE_DOMAIN_EXECUTION_ENTER | EXECUTION_EXIT
```

**Missing for evidence model:**

```text
NATIVE_DOMAIN_LEASE_RELEASE   (edgeKey, reason, released=true|false)
NATIVE_DOMAIN_HOLDER_SNAPSHOT (holderEdgeKey, leaseState) on session end / watchdog
```

Without release / snapshot events, field cannot adjudicate E2/E3.

---

## §2 — Post-hang domain state taxonomy

Map runtime facts to **admission posture** (not UI display states):

| State | Meaning | Typical observables | B2 may admit? |
|-------|---------|---------------------|---------------|
| **RETAINED** | Lease held; native `block()` may still be running | `EXECUTION_ENTER` without matching `EXIT`; `LEASE_BUSY` with same holder; B1 `WAITING_FOR_SAFE_ADMISSION` | **NO** |
| **UNKNOWN** | Watchdog fired; native thread fate unknown; slot may still show ACTIVE | `SRD_WATCHDOG_GAP` · `POST_NATIVE_CALLBACK_WAIT` · holderEdge = cause edge | **NO** |
| **STUCK** | Logical containment: `ConferenceNativeExecutionDomain.markStuck` | `LeaseState.STUCK` on holder snapshot | **NO** (releaseLease also refuses STUCK) |
| **QUARANTINED** | Domain blocks new grants | `NATIVE_DOMAIN_QUARANTINED` · `LeaseState.QUARANTINED` | **NO** |
| **IDLE** | E3 satisfied | No holder · no open watchdog gap · no quarantine | **ONLY state eligible for B2 gate** |

**Production wiring note (2026-08-25):** `markStuck` / `quarantine` exist on `ConferenceNativeExecutionDomain` but are **not** wired from SRD watchdog in coordinator production path. Hang field run therefore stayed in **de facto RETAINED/UNKNOWN**, not STUCK.

---

## §3 — B2 admission gate contract (normative)

B2 safe re-realization MUST NOT start from recovery intent alone. Admission requires a **positive idle fact chain**:

```text
RecoveryIntent.state == WAITING_FOR_SAFE_ADMISSION   (or successor B2-ready state)
        AND
DomainIdleFact == PROVEN (E3 or later tier)
        AND
Edge-scoped re-realization authorization (separate B2 IA scope)
        THEN
May schedule new Conference media realization for that edgeKey only
```

**Gate inputs (allowed):**

```text
HolderSnapshot from ConferenceNativeExecutionDomain.currentSnapshot()
NATIVE_DOMAIN_EXECUTION_EXIT / LEASE_RELEASE observability (when added)
Explicit policy facts (quarantine clear — future)
Fresh meshGeneration / pcGeneration lineage for retry attempt
```

**Gate forbidden inputs:**

```text
EDGE_FAILED terminal projection
RECOVERY_REQUESTED / RECOVERY_TERMINAL
SRD_TIMEOUT / watchdog gap alone
SESSION_END
DOMAIN_BLOCKED clearing on impact edges
Inferring idle from lease BUSY disappearing without release fact
```

**B2 MUST NOT:**

```text
Re-enter shared-factory while holderEdgeKey non-null
Retry SRD on same hung PeerConnection / same pcGeneration without new realization attempt
Force-clear lease to satisfy gate
Reset factory / PC / domain (Option C — still deferred)
Convert recovery intent → Conference availability / UI ONLINE
```

---

## §4 — Adjudication: self-heal without force-release?

### Question restated

> After native hang, can `shared-factory` return to **IDLE** without force-release or PC reset?

### Decision

```text
THEORETICALLY POSSIBLE only if:
  hung native call eventually returns AND runWithLease finally releases
  OR a future containment policy proves slot reusable without native truth

NOT PROVEN in B1 field:
  hang persisted ≥4 minutes through session end
  no EXECUTION_EXIT · no lease release observability

NOT AUTHORIZED to assume:
  "wait long enough" · session end · hangup media release
```

**Verdict:**

| Claim | Status |
|-------|--------|
| B2 needs Safe Admission Evidence Model | **ACCEPTED** (this document) |
| B2 can proceed without idle observation | **REJECTED** |
| Domain self-heals silently after hang | **UNPROVEN** — treat as **UNKNOWN** until E3 field |
| Force-release / PC reset required for any recovery | **NOT DECIDED** — out of scope; Option C still deferred |
| B1 `WAITING_FOR_SAFE_ADMISSION` implies idle will arrive | **REJECTED** — wait state is intentional non-assumption |

---

## §5 — Relationship to B1 (frozen boundary)

B1 proved:

```text
Control plane can record recovery intent without media recovery
Scenario E / D separation holds under field
Coordinator remains runnable under native hang
Recovery intent does not leak across sessions
```

B1 did **not** prove (and must not be read as):

```text
Safe admission will occur in-session
Domain becomes idle on SESSION_END
B2 retry is immediately feasible after RECOVERY_REQUESTED
```

---

## Authorization matrix (post-review)

| Track | Status |
|-------|--------|
| B1 Edge Recovery Intent | **FIELD PASS** · frozen |
| B2 Safe Re-Realization impl | **NOT AUTHORIZED** |
| Option C domain reset | **NOT AUTHORIZED** (unchanged) |
| Force-release / PC reset | **NOT AUTHORIZED** (unchanged) |
| SRD retry in hang session | **NOT AUTHORIZED** |
| **Domain Idle Observation IA** (read-only lease/holder timeline) | **RECOMMENDED NEXT** · not started |
| B2 IA (re-realization contract) | **BLOCKED** until E3 field or explicit negative result |

---

## Recommended next track (observation only)

**Domain Idle Observation IA** — read-only, no behavior change:

1. Add `NATIVE_DOMAIN_LEASE_RELEASE` + periodic / event-driven `NATIVE_DOMAIN_HOLDER_SNAPSHOT` logs.
2. Field protocol: hang → **hold session open** (no hangup) → observe whether E2/E3 ever arrives; separate run: hang → hangup → observe whether process retains holder into **next** conference.
3. Adjudicate: **self-heal YES/NO/UNPROVEN** with evidence — only then convene **B2 IA**.

Do **not** implement B2 re-realization until step 3 completes.

---

## Cross-references

- B1 field run: `talkback/logs/gct-b1-field-20260825-105501/`
- Domain lease API: `ConferenceNativeExecutionDomain.kt` · `ConferenceSrdNativeDomainAdmission.kt`
- B1 safe-admission handoff (read-only): [edge-srd-recovery-ia-v0.1-b1.md](./edge-srd-recovery-ia-v0.1-b1.md) AUTH-B1-4

---

## Decision record (single block)

```text
DECIDED:
  B2 admission requires proven Domain Idle (E3 tier minimum — subject to observation IA).
  EDGE_FAILED / RECOVERY_TERMINAL / watchdog / SESSION_END are NOT idle evidence.

NOT AUTHORIZED:
  B2 implementation · SRD retry · lease force-clear · Option C reset.

NEXT (recommended):
  Domain Idle Observation IA — close observability gap · field adjudicate self-heal question.

B1:
  FIELD PASS · frozen · no further B1 work unless regression.
```
