# PA-SR5 — Field Run Card (M01 SOURCE Generation Replace / MEMBER_REPLACED)

**Status:** **STIMULUS FROZEN** · OBS + harness **AUTHORIZED** (2026-09-10)  
**Prerequisite:** PA-SR4 **CLOSED / FIELD-PROVEN** (`meeting-product-integration-phase-a-sr4-field-closure.md`)  
**Parent:** `meeting-product-integration-phase-a-shadow-runtime-adjudication-criteria.md` (SR5 — superseded where this card is stricter)

```text
PA-SR1–SR4        CLOSED / FIELD-PROVEN
PR-PA-SR4-*       FREEZE (no further changes)
PA-SR5 stimulus   FROZEN = M01 SOURCE generation replace
OBS               incarnation/source identity — AUTHORIZED
PaSr5 harness     AUTHORIZED
Product Kotlin    only if replacement path lacks lifecycle hook (field rotate uses debug broadcast → product SOURCE succession)
NEXT              PA-SR5 field → PA-SR6 teardown
ADR-0056          KEEP
Meeting cutover   NOT AUTHORIZED
```

---

## 1. Question this gate answers

> Within the **same Meeting session H**, when M01 authoritative SOURCE advances from **G1 → G2**, peer wiring must **revoke G1**, **install G2**, resume media on G2, and **never resurrect G1**.

**Not** host UI leave (ends session). **Not** M02 voluntary leave/rejoin (revokes M02, not M01-centric). **Is** formal **source replacement / generation succession** on the product SOURCE → `MEMBER_REPLACED` path.

---

## 2. Frozen stimulus (field)

```text
Stimulus:
M01 authoritative SOURCE G1
→ force/source-trigger generation replacement (product SOURCE succession)
→ peer M02 observes old G1 revoked
→ new G2 installed
→ media resumes on G2
```

**Driver entry (authorized):** `adb broadcast` `com.talkback.appprod.debug.PA_SR5_ROTATE_LOCAL_SOURCE` on **M01** (DEBUG build).  
Invokes real `commitLocalSource` + `onLocalConferenceSourceCommitted` + peer fanout — **not** fake leave, **not** harness-only revoke.

**Rejected stimuli (SR6 / other gates):**

| Stimulus | Why rejected for SR5 |
|----------|------------------------|
| M01 host UI leave | `hangupInternal` — same H cannot reach Window C |
| M02 voluntary leave/rejoin | Revoke subject is M02; not M01-centric |

---

## 3. Frozen invariant

```text
Current runtime authority ⊆ authoritative source identity + sourceGeneration (G2)
Revoked G1: not admissible, not in catalog/live jitter/decode pool
Identity fence: sourceGeneration (= wiring incarnationId) per Profile01 contract
Do not require both sourceGeneration AND sourceInstanceId to change unless contract guarantees both
```

Field evidence must show **G2 ≠ G1** on the authoritative fence (typically `sourceGeneration`), not only “media resumed.”

---

## 4. Adjudication plane

**M02 peer** — observes M01 host source on peer catalog (`moduleId=M01`).

Capture **G1** in Window A from first `WIRING_MEMBER_INSTALLED` / `SHADOW_MEMBER_INSTALLED` with `sourceGeneration` on M02.

---

## 5. One run — three observation windows

Driver markers in `SHADOW_RUNTIME_GATE.txt`:

| Window | Marker | Intent |
|--------|--------|--------|
| **A — BASELINE G1** | `sr5Window=A` | SR4-class activity; M01 on peer at **G1** |
| **B — REVOKE G1** | `sr5Window=B` | `WIRING_MEMBER_REVOKED module=M01` · G1 dead |
| **C — INSTALL G2** | `sr5Window=C` | G2 installed · media resumes · no G1 resurrection |

### Window A — BASELINE G1 (prerequisite)

On **M02** (peer adjudication plane):

```text
SHADOW_TX_ACTIVITY (M01 host) packetsSent > 0
INGRESS_ACTIVITY packetsReceived > 0
PLAYOUT_ACTIVITY successfulPlayoutWrites > 0
WIRING_MEMBER_INSTALLED moduleId=M01 sourceGeneration=G1
```

### Window B — REVOKE G1

```text
SHADOW hook=MEMBER_REPLACED session=H module=M01 outcome=APPLIED (may also appear)
PROFILE01_SHADOW_RUNTIME phase=WIRING_MEMBER_REVOKED moduleId=M01
  sourceGeneration=G1 · incarnationId=G1
  (sourceInstanceId=I1 when present in OBS)
Runtime snapshot (M02): no further admitted/live ownership for G1
No post-revoke INGRESS/PLAYOUT attributed to G1 SSRC/generation
```

### Window C — INSTALL G2 + RESUME

```text
PROFILE01_SHADOW_RUNTIME phase=WIRING_MEMBER_INSTALLED moduleId=M01
  sourceGeneration=G2 · require G2 != G1 (or I2 != I1 if instance fence evidenced)
SHADOW hook=MEMBER_MEDIA_READY session=H module=M01 outcome=APPLIED
INGRESS_ACTIVITY resumes · PLAYOUT_ACTIVITY successfulPlayoutWrites > 0
audioTrackOwner=SHADOW_METRICS_ONLY
NEGATIVE: no OBS/admission reviving G1 after G2 boundary
```

---

## 6. PASS / FAIL / INVALID

**PASS (`PASS_PA_SR5`):** Windows A+B+C satisfied; **G2 ≠ G1** on authoritative fence; no stale-generation resurrection.

**FAIL (`FAIL_PA_SR5`):** Preconditions green; generation replace invariant violated.

**INVALID:** PRE-G/I/J/J0 break; missing logs; driver used M02 leave or M01 host leave; SR4 prerequisite not met in Window A; missing `sr5Window` markers.

---

## 7. Implementation queue

| # | Track | Status |
|---|--------|--------|
| 1 | **OBS** | `sourceGeneration`/`incarnationId` on `WIRING_MEMBER_*` / `SHADOW_MEMBER_*` / `SHADOW_MEMBER_REPLACED` |
| 2 | **Field stimulus** | `PA_SR5_ROTATE_LOCAL_SOURCE` debug broadcast → product SOURCE succession |
| 3 | **Driver** | `PaSr5` mode: SR4 baseline → rotate broadcast → windows A/B/C |
| 4 | **Adjudicate** | `phase-a-sr5-field-adjudicate.ps1` + selftest fixtures |
| 5 | **Unit** | `replaceMember` generation fence (existing); no SR4 seam changes |

**Do not** change PR-PA-SR4-TX/RX/PLAY seams except behavior-neutral OBS identity fields.

---

## 8. Harness

```powershell
.\talkback\scripts\phase-a-sr5-field-first-run.ps1
.\talkback\scripts\phase-a-sr5-field-adjudicate.ps1 -LogDir <dir>
.\talkback\scripts\phase-a-sr5-field-adjudicate-selftest.ps1
```

Orchestration: `p1c-package-field-first-run.ps1 -DriverMode PaSr5`

---

## 9. After SR5

```text
PA-SR5 PASS → PA-SR6 single Meeting end → TEARDOWN_EMPTY → Phase A closure
```
