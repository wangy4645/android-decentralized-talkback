# Domain Idle Observation IA-001

**ID:** domain-idle-observation-ia-001  
**Date:** 2026-08-25  
**Version:** v0.1  
**Type:** Implementation Authorization · Read-Only Observability + Field Protocol  
**Status:** **AUTHORIZED — OBSERVATION ONLY** · B2 implementation **UNAUTHORIZED**

**Wave:** DIO-001 — Close the native domain idle evidence gap before any B2 IA  
**Out of wave:** B2 safe re-realization · SRD retry · lease force-clear · quarantine mutation · Option C reset · any recovery behavior change

**Prerequisites (frozen):**

| Gate | Artifact | Status |
|------|----------|--------|
| Primary defect | [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md) | FROZEN |
| B1 control plane | [edge-srd-recovery-ia-v0.1-b1.md](./edge-srd-recovery-ia-v0.1-b1.md) | **FIELD PASS** |
| B2 evidence model | [b2-safe-re-realization-admission-evidence-review-20260825.md](./b2-safe-re-realization-admission-evidence-review-20260825.md) | DECIDED |
| B1 field (hang baseline) | `talkback/logs/gct-b1-field-20260825-105501/` | PASS · M03 hang · no EXIT in window |

```text
B1 FIELD PASS
        ↓
B2 Admission Evidence Review (E3 defined, gap identified)
        ↓
This IA-001 — observation only          ← AUTHORIZED here
        ↓
Implementation PR(s) — logging only
        ↓
Field A + Field B (separate captures)
        ↓
Domain Idle Adjudication
        ↓
B2 IA                                   ← NOT AUTHORIZED until adjudication
```

---

## Authorization statement (single sentence)

> **Authorize read-only observability and two field protocols** to determine whether `shared-factory` can reach **E3 Domain Idle** after Shared Native SRD Fault — without changing lease, recovery, retry, timeout, quarantine, or any media recovery behavior.

---

## Open question (must not assume an answer)

After native hang:

```text
M03 (or order-dependent failure edge)
    → NATIVE_DOMAIN_EXECUTION_ENTER
    → lease RETAINED (B1 field proved in observation window)
    → ??? natural SRD_NATIVE_CALL_EXIT / EXECUTION_EXIT / LEASE_RELEASE ???
```

B1 field proves **no EXIT within ~4 minutes through session hangup**.  
B1 field does **not** prove **permanent** stuck — only **unknown within that window**.

This IA exists to distinguish:

```text
self-heal to E3 IDLE          vs
forever no EXIT               vs
SESSION_END clears lease      vs
old lease leaks into next session
```

---

## Frozen shortcuts (carry forward)

```text
EDGE_FAILED              ≠  NATIVE_IDLE
RECOVERY_TERMINAL        ≠  LEASE_NONE
watchdog timeout         ≠  safe admission
SESSION_END              ≠  domain slot cleared
LEASE_NONE alone         ≠  E3 (must be full chain)
```

---

## E3 — Domain Idle evidence chain (normative)

**E3** is the minimum **positive** fact chain that may **inform** a future B2 admission gate.  
It is **not** B2 authorization by itself.

### Required sequence (same holder edgeKey / same hang attempt)

```text
(1) SRD_NATIVE_CALL_EXIT          — JNI / native call returned for hung attempt
        ↓
(2) NATIVE_DOMAIN_EXECUTION_EXIT  — domain execution scope completed
        ↓
(3) NATIVE_DOMAIN_LEASE_RELEASE   — releaseLease invoked; released=true logged
        ↓
(4) NATIVE_DOMAIN_HOLDER_SNAPSHOT — holderEdgeKey=NONE · leaseState=NONE
        ↓
(5) DOMAIN_IDLE_E3                — explicit adjudication marker (observation emit)
```

**Any missing link → classify as RETAINED or UNKNOWN, not E3.**

| Missing link | Classification |
|--------------|----------------|
| (1) absent after hang | **RETAINED** or **UNKNOWN** (watchdog already fired) |
| (1) present, (2) absent | **UNKNOWN** — native returned but execution scope unclear |
| (1)(2) present, (3) absent | **RETAINED** — execution ended but lease not released |
| (3) released=false | **RETAINED** — release attempted but slot kept (e.g. STUCK) |
| (4) holder non-null | **RETAINED** |
| Full chain within Field A window | **E3 IDLE observed** |

### E3 is NOT

```text
SRD_EXIT alone without NATIVE_CALL_EXIT
NATIVE_DOMAIN_EXECUTION_EXIT on a different edgeKey
MEDIA_RELEASE_COMPLETE
SHARED_FACTORY_RELEASE_EXIT
Inference from absence of LEASE_BUSY on a new request
```

---

## Authorized scope

### AUTH-DIO-1 — Lease release + holder snapshot logging

**Behavior-neutral.** Add Talkback log lines only. No control-flow change.

**Must emit:**

| Event | When | Minimum fields |
|-------|------|----------------|
| `NATIVE_DOMAIN_LEASE_RELEASE` | Every `releaseLease()` call | `edgeKey` · `reason` · `released=true\|false` · `priorHolderEdgeKey` · `priorLeaseState` |
| `NATIVE_DOMAIN_HOLDER_SNAPSHOT` | After lease grant/release; on watchdog gap; on session terminate; optional periodic while holder non-null | `holderEdgeKey=NONE\|…` · `leaseState=NONE\|ACTIVE\|STUCK\|QUARANTINED` · `trigger=` |

**Wire points (implementation guidance — not behavior change):**

```text
ConferenceNativeExecutionDomain.releaseLease     → LEASE_RELEASE
ConferenceNativeExecutionDomain.requestLease     → snapshot after grant/busy/quarantine
ConferenceSrdNativeDomainAdmission.runWithLease    → snapshot in finally path
SRD watchdog / EDGE_FAILED classification path   → snapshot (read currentSnapshot only)
Conference session terminate / clearSession hooks  → snapshot trigger=SESSION_END
```

**Must NOT add in this IA:**

```text
markStuck / quarantine calls from new production paths
timeout-based releaseLease
force-clear slot on SESSION_END
```

Existing `markStuck` / `quarantine` APIs remain **unwired** unless already called — do not wire them in DIO-001.

---

### AUTH-DIO-2 — Field A: hang后保持现场，不 hangup

**Purpose:** Does native hang **self-heal** to E3 on the **same session**?

**Protocol:**

```text
Clean start (M01/M02/M03/M04) · CH-01 · Conference
Reproduce second sequential shared-factory SRD hang (same as B1 / clean-start adjudication)
Confirm: SRD_WATCHDOG_GAP · EDGE_FAILED · RECOVERY_WAITING on failure edge
DO NOT hangup
Hold ≥ 10 minutes (or until E3 chain complete — whichever first)
Stop capture · adjudicate
```

**Primary grep (failure edge, M01 host log):**

```text
SRD_WATCHDOG_GAP
RECOVERY_WAITING_FOR_DOMAIN
SRD_NATIVE_CALL_EXIT       (same edgeKey as hang)
NATIVE_DOMAIN_EXECUTION_EXIT
NATIVE_DOMAIN_LEASE_RELEASE released=true
NATIVE_DOMAIN_HOLDER_SNAPSHOT holderEdgeKey=NONE
DOMAIN_IDLE_E3             (if emit helper added)
```

**Field A outcomes:**

| Observation | Label |
|-------------|-------|
| Full E3 chain after hang | **SELF_HEAL_E3** |
| No EXIT before capture end | **NO_SELF_HEAL_IN_WINDOW** |
| Partial chain (see table above) | **PARTIAL_* / RETAINED / UNKNOWN** |

---

### AUTH-DIO-3 — Field B: hangup 后下一场 Conference

**Purpose:** Does **old** domain lease survive **session lifecycle** and block **new** realization?

**Protocol:**

```text
Clean start · reproduce hang (same as Field A setup)
Confirm hang + RETAINED snapshot
M01 hangup (SESSION_END · RECOVERY_TERMINAL expected from B1)
Wait ≥ 60s (process stays alive — no pm clear)
Start NEW Conference on CH-01 (new session id)
Attempt normal 4p join / sequential SRD (do not force order)
Capture through second SRD attempt or ≥ 5 minutes
```

**Primary grep:**

```text
Session 1: RECOVERY_TERMINAL · NATIVE_DOMAIN_HOLDER_SNAPSHOT trigger=SESSION_END
Between sessions: holder snapshot series
Session 2: NATIVE_DOMAIN_LEASE_REQUEST / GRANTED / BUSY
           holderEdgeKey on first lease attempt
           Compare to session 1 failure edgeKey
```

**Field B outcomes (lease safety, not conference success):**

| Observation | Label |
|-------------|-------|
| Session 1 hang → holder NONE before session 2 | **SESSION_BOUNDARY_CLEARED** |
| Session 2 starts with holder = session 1 edge | **LEASE_LEAK_CROSS_SESSION** |
| Holder NONE but session 2 SRD still fails | **ADMISSION_SEPARATE_FROM_LEASE** (lease safe; other fault) |
| Session 2 reaches E3 on new edge | **NEW_SESSION_E3** (orthogonal positive) |

**Not the primary pass condition:** “second conference works end-to-end.”

---

### AUTH-DIO-4 — Observation-only invariant

All DIO-001 code and field runs **MUST NOT**:

```text
force-release lease
PC reset / native reset
Factory partition or redesign
SRD retry / re-realization
lease timeout / watchdog release
quarantine state mutation (new wiring)
B2 recovery actions
“helpful” recovery shortcuts for cleaner field results
```

Allowed: **log**, **snapshot read**, **field hold / hangup as protocol steps**.

---

## Domain state labels (field adjudication vocabulary)

| Label | Meaning |
|-------|---------|
| **RETAINED** | Holder non-null or release=false after hang |
| **UNKNOWN** | Watchdog fired; native fate unclear; incomplete chain |
| **E3 IDLE** | Full five-link chain observed |
| **SELF_HEAL_E3** | Field A: E3 after hang without hangup |
| **NO_SELF_HEAL_IN_WINDOW** | Field A: no E3 before capture end |
| **LEASE_LEAK_CROSS_SESSION** | Field B: old holder into new session |
| **SESSION_BOUNDARY_CLEARED** | Field B: holder none at new session start |

---

## Final adjudication matrix (gates B2 IA)

| Field result | Meaning | B2 IA |
|--------------|---------|-------|
| Field A → full **E3** | Natural self-heal exists on same domain | **May convene** B2 IA to study safe re-realization |
| Field A → **never E3** in window | No natural recovery evidence on same session | **Hold** B2 · evaluate recovery **capability** track (not impl) |
| Field B → old lease **retained** in new session | Recovery cannot rely on session lifecycle alone | **Hold** B2 · session-bound cleanup question |
| Field B → lease **E3** but new admission fails | Lease safety vs realization fault **decoupled** | B2 IA may proceed on admission slice only |
| Cannot obtain **E3** evidence | Do not assume idle | **B2 remains UNAUTHORIZED** |

---

## Implementation deliverables (DIO-001 PR scope)

1. `NATIVE_DOMAIN_LEASE_RELEASE` observability in `ConferenceNativeExecutionDomain` / admission wrapper.
2. `NATIVE_DOMAIN_HOLDER_SNAPSHOT` at triggers listed in AUTH-DIO-1.
3. Optional `DOMAIN_IDLE_E3` one-line emit when implementation detects full chain locally (desk-testable); field may also adjudicate from grep without helper.
4. Unit tests: release true/false · snapshot after grant · **no behavior change** on existing admission tests.

**Desk gate before field:**

```text
ConferenceNativeExecutionDomainTest + observability tests PASS
Normal SRD path logs: GRANT → ENTER → EXIT → LEASE_RELEASE released=true → holder NONE
Simulated releaseLease(false) path logs released=false · holder RETAINED
```

---

## Field logistics

| Item | Value |
|------|-------|
| Devices | M01 `HTUBB21B09220661` · M02 `DSJ-2407041` · M03 `MDX0220416001963` · M04 `DSJ-2407011` |
| Package | `com.talkback.appprod` |
| Baseline | B1 build + DIO-001 observability |
| Log tag | `Talkback:I` |
| Suggested dirs | `talkback/logs/dio-field-a-<stamp>/` · `talkback/logs/dio-field-b-<stamp>/` |

**Field A:** do **not** hangup until protocol complete.  
**Field B:** session 1 hangup required; session 2 **no pm clear** unless adjudication notes cold-start variant separately.

---

## Explicitly NOT authorized

```text
B2 safe re-realization implementation
B2 IA (until Domain Idle Adjudication signed)
force-release · PC reset · native reset
Factory redesign / partition
SRD retry · lease timeout · quarantine wiring
markStuck from watchdog (new wiring)
SESSION_END force-clear domain slot
Using DIO field to “validate recovery works”
```

---

## Relationship to B1

B1 remains **frozen** · **FIELD PASS**. DIO-001 does not extend B1 scope.

```text
B1 answers: recovery intent control plane under hang
DIO-001 answers: can domain ever become provably idle again?
```

B1 `RECOVERY_WAITING_FOR_SAFE_ADMISSION` must **not** be interpreted as progress toward E3 during DIO field — wait state is expected until E3 chain or terminal.

---

## Cross-references

- E3 tier definition: [b2-safe-re-realization-admission-evidence-review-20260825.md](./b2-safe-re-realization-admission-evidence-review-20260825.md) §1
- Domain lease API: `ConferenceNativeExecutionDomain.kt` · `ConferenceSrdNativeDomainAdmission.kt`
- Hang baseline: `talkback/logs/gct-b1-field-20260825-105501/`

---

## Decision record

```text
AUTHORIZED:
  DIO-001 observation-only logging (AUTH-DIO-1)
  Field A — no hangup self-heal watch (AUTH-DIO-2)
  Field B — cross-session lease leak watch (AUTH-DIO-3)
  Read-only invariant (AUTH-DIO-4)
  E3 five-link evidence chain (normative)

NOT AUTHORIZED:
  B2 implementation · B2 IA · any recovery behavior change

NEXT AFTER MERGE + FIELD:
  Domain Idle Adjudication doc → then B2 IA decision only if matrix allows
```
