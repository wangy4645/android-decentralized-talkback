# Primary Native SRD Fault — Minimal Contrast Field Run Card

**ID:** primary-native-srd-fault-minimal-contrast-run-card-20260825  
**Date:** 2026-08-25  
**Authorization:** [primary-native-srd-fault-rca-20260825.md](./primary-native-srd-fault-rca-20260825.md) **AUTHORIZED**  
**Type:** Field protocol · observation only  
**Implementation:** **NOT AUTHORIZED**  
**Matrix review:** **ACCEPTED** (2026-08-25) — experiment design frozen  
**Field execution:** **AUTHORIZED** — DIO-1 build · **final** clean RUN 1a + RUN 1b only · then **STOP**

---

## Review adjudication (frozen)

| # | Ruling | Status |
|---|--------|--------|
| 1 | **1a + 1b mandatory** — accept order `M02→M03→M04` / `M03→M02→M04`; **1b cannot be skipped** | **ACCEPT** |
| 2 | **Exp 1 / 2 / 3 on both runs** — Exp 3 confirm E3 after SRD#1; no multi-round C archaeology | **ACCEPT** |
| 3 | **No desk final adjudication** — IA-001 logs may pre-organize Exp 2 fields only; authoritative capture = DIO-1 field | **ACCEPT** |
| 4 | **Next step:** DIO-1 build → clean start 1a → clean start 1b → **adjudicate · STOP** | **AUTHORIZED** |
| 5 | **Implementation** | **NONE** until post-1b adjudication |
| 6 | **Dirty scout** `pnsrd-20260825-112406` (session `03b0dcad…`) | **INVALID** (no pm clear; M02 pcGen=391) · **C weakened**: SRD#1→E3 then SRD#2 TIER_JNI hang |

**Hard stop after 1a+1b.** No re-run 1a, no device swap, no extra order variants.

**Remaining discrimination after dirty scout:** primarily **A vs B vs D**. Exp 3 only needs E3 confirmed on both clean SRD#1 paths.

**If clean 1a = M02 PASS→M03 HANG and clean 1b = M03 PASS→M02 HANG** → promote **A** to primary · stop field archaeology · open minimal fix design.

---

## Tags (required on every capture / adjudication)

```text
Primary Native SRD Fault · Minimal Contrast · OBS ONLY
Hypothesis A/B/C/D · Exp 1/2/3
B1 STOP · DIO Field STOP · B2 UNAUTHORIZED
NO force-release · NO PC reset · NO native reset · NO Factory partition
```

---

## Goal (one sentence)

Discriminate **order (A)** vs **PC/SDP input (B)** vs **post-SRD#1 non-idle (C)** vs **other shared-runtime state (D)** — not re-prove that a second SRD can hang.

---

## Topology pre-gate

```text
HOST           = M01  HTUBB21B09220661   (USB log REQUIRED)
PEER_A         = M02  DSJ-2407041
PEER_B         = M03  MDX0220416001963   (primary contrast)
PEER_C         = M04  DSJ-2407011        (blast-radius corroboration)
CHANNEL        = CH-01
TOPOLOGY       = 4-participant group conference, M01 host / anchor
PACKAGE        = com.talkback.appprod
```

If `CURRENT_ANCHOR != M01` → **INVALID RUN** (do not adjudicate).

---

## Build pre-gate

```text
REQUIRED markers:
  SRD_NATIVE_CALL_ENTER / EXIT
  NATIVE_DOMAIN_EXECUTION_ENTER / EXIT
  NATIVE_DOMAIN_LEASE_RELEASE
  NATIVE_DOMAIN_HOLDER_SNAPSHOT
  DOMAIN_IDLE_E3
  CONFERENCE_OFFER_LINEAGE APPLY_REMOTE_ANSWER
  SRD_WATCHDOG_GAP
```

Build must include **DIO-1 observability** ([domain-idle-observation-ia-001.md](./domain-idle-observation-ia-001.md)).  
B1 recovery intent enabled is **OK** (control plane frozen PASS) — do **not** treat recovery intent as success metric.

### Clean start (required before each session)

```text
pm clear com.talkback.appprod   on M01 · M02 · M03 · M04
Fresh install same APK all devices
Verify HELLO / roster visible before join
```

---

## USB / log posture

| Device | Requirement |
|--------|-------------|
| M01 host | **Mandatory** full `adb logcat` for entire session through hangup |
| M03 | Optional; use when available as failing/passing edge corroboration |
| M04 | Optional; DOMAIN_BLOCKED corroboration without USB is acceptable if host log shows L2 chain |
| M02 | Optional |

**Rule:** Whichever peer has complete native-side log becomes **edge control** for that capture. Missing peer USB does **not** invalidate run if host chain is complete.

Log folder naming:

```text
talkback/logs/pnsrd-<YYYYMMDD-HHMMSS>/
  M01-talkback.log
  M03-talkback.log          (if captured)
  run-notes.md              (accept order, timestamps, UI notes)
```

---

## Session procedure (common)

1. Host creates CH-01 group conference; invite M02, M03, M04.
2. Wait for host `createOffer` / `GROUP_INVITE` per peer (expect dual-offer lifecycle anomaly — **do not abort**).
3. Execute **accept order per experiment** (below). Leave **≥3 s** between accepts unless noted.
4. Do **not** hang up until: watchdog fires on failure edge **or** all host SRD complete **or** 5 min idle after last accept.
5. Hang up from host; confirm session end in logs.
6. Pull logs; run adjudication worksheet (bottom).

**Forbidden during run:** retry join on same session after hang · toggle WiFi · kill app · change timeout · manual SRD trigger.

---

## Field execution order (normative)

```text
DIO-1 build → install M01 · M02 · M03 · M04
        ↓
Clean start (pm clear all four)
        ↓
RUN 1a — accept M02 → M03 → M04
        ↓
Full capture (host log through hangup) · pull logs → pnsrd-*-1a/
        ↓
Clean start (pm clear all four)          ← REQUIRED; do not skip
        ↓
RUN 1b — accept M03 → M02 → M04
        ↓
Full capture · pull logs → pnsrd-*-1b/
        ↓
Adjudicate Exp 1 / 2 / 3 from both captures (not from prior IA-001 alone)
```

### Per-run record (required)

```text
SRD#1 (ordinal 1)
  remote=
  SRD_NATIVE_CALL_ENTER / EXIT · elapsedMs=
  E3 chain: EXECUTION_EXIT · LEASE_RELEASE(released=) · HOLDER_SNAPSHOT · DOMAIN_IDLE_E3

SRD#2 (ordinal 2)
  remote=
  SRD_NATIVE_CALL_ENTER? · EXECUTION_ENTER
  hang tier: TIER_JNI | TIER_PRE_JNI
  SRD_WATCHDOG_GAP · holderEdge=

SRD#3 (ordinal 3, M04)
  NATIVE_DOMAIN_LEASE_BUSY? · DOMAIN_BLOCKED? · SRD_ENTER?
```

**Hang tier (do not collapse):**

```text
TIER_JNI:     SRD_NATIVE_CALL_ENTER without SRD_NATIVE_CALL_EXIT
TIER_PRE_JNI: NATIVE_DOMAIN_EXECUTION_ENTER without SRD_NATIVE_CALL_ENTER
```

Both are valid `SRD_TIMEOUT` outcomes but imply different failure layers.

---

## Experiment 1 — Order inversion

**Hypothesis under test:** **A** (sequencing) vs **B** (edge-specific)

### Runs

| Run | Accept order (host observes APPLY_REMOTE_ANSWER sequence) | Expected if **A** holds |
|-----|-----------------------------------------------------------|-------------------------|
| **1a** (baseline) | M02 → M03 → M04 | 1st SRD PASS · 2nd HANG · M04 DOMAIN_BLOCKED |
| **1b** (inverted) | M03 → M02 → M04 | 1st SRD PASS · 2nd HANG · M04 DOMAIN_BLOCKED; **failure edge swaps** with 2nd accept |

**Both runs mandatory.** Skipping 1b cannot discriminate ordinal **A** vs fixed peer **B**.

Third accept (M04) is **not** the primary variable — it confirms blast radius only.

### Operator steps

**Run 1a:** M02 accepts first · wait for host `SRD_NATIVE_CALL_EXIT` or CONNECTING on M02 · then M03 accepts · then M04.

**Run 1b:** M03 accepts first · wait for host SRD#1 complete · then M02 accepts · then M04.

### Record per run

```text
ordinal=1  remote=  APPLY ts=  ENTER ts=  EXIT ts=  elapsedMs=  result=PASS|HANG
ordinal=2  remote=  APPLY ts=  ENTER ts=  EXIT ts=  elapsedMs=  result=PASS|HANG
ordinal=3  remote=M04  LEASE_BUSY?  DOMAIN_BLOCKED?  SRD_ENTER?
failureEdge=
holderEdgeAtWatchdog=
```

### Exp 1 adjudication

| Outcome | Verdict |
|---------|---------|
| Hang always on **ordinal 2**; failure edge follows 2nd accept | **A** primary |
| Hang always on **same remote** regardless of ordinal | **B** primary |
| Both ordinals PASS in 1b | **A** refuted for deterministic rule — capture INVALID for Exp1 unless explained |
| Non-deterministic ordinal | **D** contributing — note both runs |

---

## Experiment 2 — Native input equivalence

**Hypothesis under test:** **B** (input) vs **D** (opaque runtime)

Runs on **both** 1a and 1b captures. Compare **ordinal-1 PASS** vs **ordinal-2 HANG** within each session; cross-run compare whether field deltas follow **ordinal** or **peer**.

Prior IA-001 clean log may pre-organize known fields — **not** final Exp 2 adjudication without DIO-1 E3 context from field.

### Fields to extract (from `SRD_NATIVE_CALL_ENTER` or last pre-JNI snapshot)

```text
signalingState
connectionState
iceConnectionState
localDesc / remoteDesc
answerSdpBytes / answerSdpLines
pcHash
pcGeneration
conferenceGeneration
factoryHash
factoryHit                    (from preceding MEDIA_PROVISIONED)
offerLineageId
realizationAttemptId
hostLocalOfferLineageId
lineageCorrelation
attachedPcCount
trackId
thread / tid
```

Also from `APPLY_REMOTE_ANSWER`:

```text
offerLineageId · pcGeneration · pcHash · answerSdpBytes · lineageCorrelation
```

### Comparison rules

```text
MATCH on both paths is EXPECTED — not sufficient to close RCA
Document every field delta PASS vs HANG
If all listed fields identical except remote= / edgeKey= → lean B refuted for SDP/signaling; lean A or D
If systematic delta (e.g. signalingState, attachedPcCount, factoryHit) → lean B or D
```

### Optional desk follow-up (not field)

SDP hash compare if `answerSdpBytes` equal but suspicion remains — **out of run card scope** unless bytes differ.

### Exp 2 adjudication

| Outcome | Verdict |
|---------|---------|
| Stable field delta on failing ordinal across 1a+1b | **B** or **D** (name the delta) |
| No delta except peer identity; order rule holds | **A** reinforced |
| Same ordinal, different peer hangs with different deltas | **B** primary |

---

## Experiment 3 — Post-SRD#1 idle (E3)

**Hypothesis under test:** **C** (domain not idle before SRD#2)

Uses DIO-1 markers. Applies to **SRD#1 (PASS)** in **both 1a and 1b**.

**Why 1b matters for Exp 3:** if E3 appears after M03-first but not M02-first (or vice versa), idle may depend on **which peer completes SRD#1** — discriminates **C** vs **A/D**.

### Required chain after SRD#1

```text
SRD_NATIVE_CALL_EXIT
  ↓
NATIVE_DOMAIN_EXECUTION_EXIT
  ↓
NATIVE_DOMAIN_LEASE_RELEASE     released=true REQUIRED for E3
  ↓
NATIVE_DOMAIN_HOLDER_SNAPSHOT   holderEdgeKey=NONE · leaseState=NONE
  ↓
DOMAIN_IDLE_E3
  ↓
(time gap)
  ↓
SRD#2 NATIVE_DOMAIN_LEASE_REQUEST / EXECUTION_ENTER
```

### Critical guard

```text
SRD#1 EXIT alone  ≠  domain idle
Missing LEASE_RELEASE after EXECUTION_EXIT  →  C candidate
LEASE_RELEASE released=false               →  C candidate
HOLDER_SNAPSHOT holder≠NONE before SRD#2   →  C candidate
E3 present but SRD#2 HANG                  →  C refuted as sole cause; lean B or D
```

### Also capture at SRD#2 watchdog (if hang)

```text
SRD_WATCHDOG_GAP missing= lastEvent= state= holderEdge=
NATIVE_DOMAIN_HOLDER_SNAPSHOT (if any post-watchdog)
Was SRD_NATIVE_CALL_ENTER seen for SRD#2?
```

B1 field (`7a78e333…`) established **TIER_PRE_JNI** (see Field execution order). Record tier on every SRD#2 hang.

### Exp 3 adjudication

| Outcome | Verdict |
|---------|---------|
| No E3 between SRD#1 EXIT and SRD#2 ENTER (either run) | **C** primary |
| E3 in 1a but not 1b (or reverse) — tied to SRD#1 peer | **C** + peer-specific lifecycle |
| E3 complete in **both** runs; SRD#2 still hangs | **C** refuted as sole cause · **B** or **D** |
| No EXECUTION_EXIT after SRD#1 EXIT | **C** / lifecycle instrumentation gap |

---

## Combined adjudication table

Fill after **both** 1a and 1b complete:

| Result | RCA direction |
|--------|---------------|
| Always 2nd ordinal hangs; inverts with accept order | **A** sequencing / shared runtime |
| Fixed peer/PC hangs regardless of order | **B** PC/edge-specific |
| SRD#1 no E3 before SRD#2 | **C** domain lifecycle / lease |
| E3 OK but SRD#2 hangs | **B** or **D** native SRD / input |
| Symmetric inputs; nondeterministic 2nd ordinal | **D** nondeterminism |

**Primary hypothesis:** __________  
**Contributing:** __________  
**Recommended next IA (no impl yet):** __________

---

## Invalid run conditions

```text
anchor != M01
pm clear skipped
host log incomplete (missing APPLY or SRD_ENTER for ordinals 1–2)
operator changed accept order mid-session without noting
WiFi flap / app restart mid-session
same session reused after hang without clean start
```

---

## Explicit prohibitions (repeat)

```text
force-release · PC reset · native reset · Factory partition
SRD retry · watchdog timeout change
pending de-dup · double-offer fix
Claiming PASS because B1 recovery intent behaved
Claiming PASS because UI showed “in meeting” on one peer
```

---

## Post-capture grep checklist (host M01)

```bash
# Ordinal timeline
rg "APPLY_REMOTE_ANSWER|SRD_NATIVE_CALL_|NATIVE_DOMAIN_EXECUTION_|LEASE_RELEASE|DOMAIN_IDLE_E3|SRD_WATCHDOG|DOMAIN_BLOCKED" M01-talkback.log

# Per-edge holder
rg "NATIVE_DOMAIN_HOLDER_SNAPSHOT|NATIVE_DOMAIN_LEASE_BUSY" M01-talkback.log
```

---

## Relationship to prior captures

| Capture | Use |
|---------|-----|
| `gct-ia001-clean-start-20260825-102652/` | Exp 2 field pre-analysis only · **not** final adjudication |
| `gct-b1-field-20260825-105501/` | TIER_PRE_JNI precedent · **no E3** — not authoritative for Exp 3 |
| New `pnsrd-*-1a/` · `pnsrd-*-1b/` | **Authoritative** for Exp 1 / 2 / 3 |

---

## After field

1. File adjudication appendix under `docs/analysis/` (or extend this run card with results).
2. **Do not** open implementation PR until Primary Native SRD Fault Adjudication names primary hypothesis.
3. Review experiment matrix with team **before** any native lifecycle code change.
