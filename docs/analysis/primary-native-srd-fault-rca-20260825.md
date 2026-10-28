# Primary Native SRD Fault — RCA

**ID:** primary-native-srd-fault-rca-20260825  
**Date:** 2026-08-25  
**Type:** RCA · Root-Cause Hypothesis Matrix · Experiment Authorization  
**Status:** **AUTHORIZED — OBSERVATION / EXPERIMENT ONLY**  
**Matrix review:** **ACCEPTED** (2026-08-25)  
**Field execution:** **AUTHORIZED** — DIO-1 build · RUN 1a + 1b · clean start between runs  
**Implementation:** **NOT AUTHORIZED**

**Wave:** Primary Native SRD Fault — replace DIO Field A/B as active line  
**Out of wave:** B2 safe re-realization · force-release · PC reset · native reset · Factory partition · SRD retry / timeout change · pending de-dup · double-offer fix · DIO Field A/B protocol

**Run card:** [primary-native-srd-fault-minimal-contrast-run-card-20260825.md](./primary-native-srd-fault-minimal-contrast-run-card-20260825.md)

**Prerequisites (frozen):**

| Gate | Artifact | Status |
|------|----------|--------|
| Primary defect framing | [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md) | **FROZEN** |
| B1 control plane | [edge-srd-recovery-ia-v0.1-b1.md](./edge-srd-recovery-ia-v0.1-b1.md) | **FIELD PASS · STOP** |
| DIO observability | [domain-idle-observation-ia-001.md](./domain-idle-observation-ia-001.md) | **IMPL · Field protocol STOP** — logs reused here |
| Lineage RCA (closed) | [m03-host-pc-generation-ownership-lineage-rca-20260824.md](./m03-host-pc-generation-ownership-lineage-rca-20260824.md) | CLOSED · not primary line |

```text
B1 FIELD PASS (control plane) · STOP
DIO Field A/B · STOP
        ↓
This RCA — hypothesis discrimination only     ← AUTHORIZED here
        ↓
Minimal contrast field (run card)
        ↓
Primary Native SRD Fault Adjudication
        ↓
Implementation review (lifecycle fix / containment / recovery)   ← NOT AUTHORIZED until adjudication
```

---

## Authorization statement (single sentence)

> **Authorize read-only field experiments** to discriminate **why** the second sequential host SRD on `shared-factory` may hang — without changing SRD behavior, lease policy, recovery, timeout, quarantine, or any native reset capability.

---

## What this RCA is **not** trying to prove

```text
NOT the goal:
  “第二个 SRD 有时会挂”   — already frozen (two independent captures + B1 field)
  “B1 recovery intent 正确” — already FIELD PASS
  “hang 后 lease 被 retain” — already frozen
  “M03 是唯一失败边” — already refuted
```

---

## What this RCA **is** trying to prove

Discriminate among four hypotheses:

| ID | Hypothesis | Meaning |
|----|------------|---------|
| **A** | **Order / sequencing dependency** | Hang tracks **second sequential** host SRD on `shared-factory`, not a fixed peer identity |
| **B** | **PC / SDP / signaling input dependency** | Hang tracks a **specific edge / PC / SDP / signaling precondition**, even when accept order changes |
| **C** | **Post-SRD#1 domain not truly idle** | SRD#1 logs `EXIT` but native domain never reaches **E3 idle** before SRD#2 enters |
| **D** | **Other shared-runtime state dependency** | Hang depends on cumulative shared-factory / attached-PC / factory state not captured by A–C alone |

Hypotheses are **not mutually exclusive**. Adjudication may assign **primary** + **contributing** labels.

---

## Frozen primary defect (carry forward)

```text
Shared Native SRD Fault

shared-factory
    + sequential host SRD (setRemoteDescription answer)
    → native call may never return (JNI non-return) OR stall before observable JNI EXIT

failure edge     = order-dependent (varies with accept / apply sequence)
blast radius     = lease-contending edges → DOMAIN_BLOCKED (M04-class corroboration)
```

**Do not write:** “M03 SRD hang” · “Recovery broken” · “fix recovery timeout” as primary defect.

---

## Prior evidence (baseline for experiments)

### Cross-run fingerprint (already frozen)

| Run | Session | 1st host SRD | 2nd host SRD | 3rd edge |
|-----|---------|--------------|--------------|----------|
| IA-001 clean | `3fe43938…` | M03 **PASS** 49ms | M02 **HANG** | M04 DOMAIN_BLOCKED |
| IA-001 dirty | `314a316e…` | M02 **PASS** 69ms | M03 **HANG** | M04 DOMAIN_BLOCKED |
| B1 field | `7a78e333…` | M02 **PASS** ~79ms | M03 **HANG** | M04 DOMAIN_BLOCKED |

**Strong prior:** first sequential SRD **PASS** · second **HANG** · third edge **DOMAIN_BLOCKED** without entering SRD.

### What prior captures already exclude

```text
lineageCorrelation=MISMATCH on failing path     — NOT observed on adjudicated runs
fixed device always fails                      — NOT observed (M03 PASS in clean start)
double createOffer / pending drain alone       — NOT sufficient (PASS edges share anomaly)
B1 recovery intent cross-session leak          — NOT observed (RECOVERY_TERMINAL SESSION_END)
```

### What prior captures do **not** yet discriminate

```text
Order inversion (A vs B) under controlled accept sequence
Native-input equivalence at JNI boundary (MATCH ≠ native equivalence)
SRD#1 post-EXIT domain idle (E3 chain) before SRD#2
Attached-PC / factory cumulative state at SRD#2 ENTER
```

### Hang observation points (do not collapse)

Prior captures show hang **at or before** observable JNI completion:

```text
Case 1: SRD_NATIVE_CALL_ENTER → (no SRD_NATIVE_CALL_EXIT)     — IA-001 clean, M02
Case 2: NATIVE_DOMAIN_EXECUTION_ENTER → watchdog missing SRD_EXIT — B1 field, M03
```

Experiments must record **last observable event** before watchdog — not assume Case 1 only.

---

## Experiment matrix (normative)

Detailed steps: [run card](./primary-native-srd-fault-minimal-contrast-run-card-20260825.md).

| Exp | Question | Method | Primary read |
|-----|----------|--------|--------------|
| **1** | Order vs edge identity | Accept-order inversion **A→B** vs **B→A** (M02/M03); M04 accept last | Hang follows **ordinal position** or **peer/PC** |
| **2** | Native input equivalence | Diff SRD#1 vs SRD#2 `SRD_NATIVE_CALL_ENTER` fields + APPLY snapshot | Systematic delta on failing ordinal only |
| **3** | Post-SRD#1 idle | E3 chain after SRD#1 `EXIT`; holder snapshot immediately before SRD#2 `ENTER` | **C** confirmed / refuted |

All three experiments run on **two mandatory field captures**:

```text
RUN 1a  M02 → M03 → M04
RUN 1b  M03 → M02 → M04   (clean start between runs)
```

Exp 1 requires both runs to invert order. Exp 2 compares fields within and across runs. Exp 3 requires E3 after SRD#1 in **both** runs to test whether idle depends on which peer is first SRD.

Prior IA-001 logs: Exp 2 pre-analysis only — **not** final adjudication (missing DIO-1 E3 chain).

---

## Adjudication rubric

| Observation | Primary RCA direction | Notes |
|-------------|----------------------|-------|
| Hang **always** on 2nd sequential SRD; inverts with accept order | **A** sequencing / shared-runtime cumulative state | Supports lifecycle fix on shared-factory between SRDs |
| Hang **always** on same peer/PC regardless of order | **B** edge/PC-specific input or PC object state | May still share factory; fix may be per-PC admission |
| SRD#1 `SRD_NATIVE_CALL_EXIT` but **no E3** before SRD#2 `ENTER` | **C** domain lifecycle / lease state | Do **not** infer idle from `EXIT` alone |
| **E3 after SRD#1** but SRD#2 still hangs | Native SRD op or SRD#2-specific input (**B** or **D**) | Rules out “stale lease blocked SRD#2 admission” as sole cause |
| Order, PC, input fields symmetric; 2nd ordinal still hangs nondeterministically | **D** shared native runtime nondeterminism | May need native-layer instrumentation beyond Java logs |
| SRD#1 PASS + SRD#2 PASS (both ordinals) in inversion run | **A** refuted as deterministic rule | Re-open capture conditions; do not claim product fixed |

**Adjudication output must name:** primary hypothesis · contributing hypotheses · **NOT AUTHORIZED** fix class · recommended next IA (if any).

---

## Required observability (field build)

Minimum log markers (DIO-1 + existing IA-001):

```text
SRD_NATIVE_CALL_ENTER / EXIT
NATIVE_DOMAIN_EXECUTION_ENTER / EXIT
NATIVE_DOMAIN_LEASE_RELEASE          released=true|false
NATIVE_DOMAIN_HOLDER_SNAPSHOT        holderEdgeKey · leaseState
DOMAIN_IDLE_E3                       full chain only
CONFERENCE_OFFER_LINEAGE APPLY_REMOTE_ANSWER lineageCorrelation
SRD_WATCHDOG_GAP                     missing= · lastEvent= · holderEdge=
CONFERENCE_FAILURE_L1/L2             EDGE_FAILED · DOMAIN_BLOCKED
```

**E3 chain (normative, from DIO-1):**

```text
SRD_NATIVE_CALL_EXIT
  → NATIVE_DOMAIN_EXECUTION_EXIT
  → NATIVE_DOMAIN_LEASE_RELEASE (released=true)
  → NATIVE_DOMAIN_HOLDER_SNAPSHOT (holderEdgeKey=NONE)
  → DOMAIN_IDLE_E3
```

> **Do not treat SRD#1 `EXIT` as domain idle.** E3 is the only positive idle fact.

---

## Device / log posture

```text
HOST (required USB log)     = M01 HTUBB21B09220661
Primary contrast peers      = M03 MDX0220416001963 · M04 DSJ-2407011
Corroboration peer          = M02 DSJ-2407041
```

- **M03 / M04** remain the primary pass/fail / blast-radius contrast pair.
- **Not required:** both M03 and M04 USB logs in every run.
- **Required:** host M01 full native SRD chain.
- **Optional corroboration:** any peer USB log; failing or passing edge log elevates that device to **control** for that capture.

---

## Explicit prohibitions (field + implementation)

```text
force-release
PC reset
native reset
Factory partition
SRD retry / timeout budget change
pending de-dup / double-offer suppression
B2 safe re-realization
DIO Field A/B long-soak protocol
Using field PASS here as “product join fixed”
```

---

## Success criteria (this wave)

```text
Both RUN 1a and RUN 1b complete (DIO-1 build, clean start each)
Adjudication table filled for Exp 1–3 from pnsrd-*-1a/ and pnsrd-*-1b/
Exp 3 E3 evidence recorded for SRD#1 in both runs
Primary hypothesis selected with evidence cites (session + log lines)
Implementation review scope drafted — behavior change still NOT AUTHORIZED
```

**Not success:** all edges CONNECTED · hang eliminated · B2 unlocked.

---

## References

- B1 field: `talkback/logs/gct-b1-field-20260825-105501/` (session `7a78e333…`)
- IA-001 clean: `talkback/logs/gct-ia001-clean-start-20260825-102652/`
- IA-001 dirty: `talkback/logs/gct-ia001-lifecycle-obs-20260825-101530/`
- B2 evidence model: [b2-safe-re-realization-admission-evidence-review-20260825.md](./b2-safe-re-realization-admission-evidence-review-20260825.md)
