# ADR-0065 Appendix B — Patch-2C Field Verification Procedure

**Status:** **Appendix B Review PASS** · **ONE FIELD VERIFICATION AUTHORIZED**  
**Date:** 2026-09-20  
**Adjudication:** [adr0065-appendix-b-adjudication.md](./adr0065-appendix-b-adjudication.md)  
**Parents:** [0065 Patch-2C contract](../adr/0065-patch-2c-activation-authority-contract.md) · [0065 Patch-2 obligation](../adr/0065-patch-2-pairwise-edge-obligation-reactivation-contract.md)

```text
Appendix B Review:       PASS (B1–B5)
Field execution:         ONE M02↔M04 RUN AUTHORIZED
Patch-2C:                IMPLEMENTED / UNIT PASS (desk)
M02↔M04:                 OPEN / NOT FIELD-VERIFIED
Production Meeting:      UNCHANGED
8–10 / soak / Layer 1 / Meeting:  NOT AUTHORIZED
```

---

## Purpose (frozen — narrow)

Appendix B is **not** a new qualification track and **not** a re-proof of ADR-0065 desk contracts.

It answers exactly one question:

> **Does Patch-2C production wiring complete one legal self-heal on the real M02↔M04 fault shape?**

**Scope:** single field verification · single peer pair · single run · single verdict.

---

## Target

| Item | Value |
|------|-------|
| **Target edge** | M02 ↔ M04 (canonical pairwise edge) |
| **Offerer (Perfect Negotiation)** | M02 |
| **Answerer** | M04 |
| **Topology** | GROUP mesh · four-node canonical roster including M02 and M04 |
| **Shape anchor** | Pre-2C repro `talkback/logs/adr0065-sync-stuck-repro-20260918-1705/` |

**Required production chain (outcome, not log script):**

```text
MEDIA_PENDING
  → reevaluation / checking grace
  → ACTIVATION_REQUIRED
  → GIDR INVALIDATED_RETRY_ELIGIBLE
  → PermitRetry
  → existing GIE dispatch
  → ICE CONNECTED
  → obligation SATISFIED
  → GROUP READY (topology no longer stuck on target edge)
```

---

## Explicit exclusions (frozen)

Appendix B **MUST NOT**:

- Re-prove Patch-2A / Patch-2B / Patch-2C unit contracts
- Expand to 8–10 participant soak
- Run a second peer pair
- Run a confirmation re-run after PASS
- Re-run after FAIL (“insurance” attempt)
- Validate Layer 1 membership authority continuity
- Change membership / roster semantics for the test
- Exercise Meeting / production Meeting policy
- Add new observability solely for field verification

---

## 1. Artifact identity (valid-run prerequisite)

Record in `ARTIFACT.txt` **before** stimulus:

| Field | Required |
|-------|----------|
| Source commit | Full git SHA of the APK build |
| APK identity | Path / label **and SHA256** |
| versionName / versionCode | Record if present |
| Device install consistency | M01 / M02 / M03 / M04 same artifact |
| Patch inclusion | Build **includes Patch-2A + Patch-2B + Patch-2C** |
| Session / channel id | As observed |
| Stimulus start | Operator local time |

**Artifact identity not established or not provable → INCONCLUSIVE.** Must not be scored as production FAIL.

---

## 2. Devices and roster (valid-run prerequisite)

| Role | Module | Reference serial |
|------|--------|------------------|
| Initiator / anchor (typical) | M01 | `HTUBB21B09220661` |
| **Offerer (target edge)** | **M02** | `2d73067a` |
| Peer | M03 | `MDX0220416001963` |
| **Answerer (target edge)** | **M04** | `DSJ-2407011` |

Requirements:

- All four modules on the **same operational SSID** (field norm: `happy`)
- Canonical GROUP roster **M01, M02, M03, M04** for the session under test
- Target edge M02↔M04 **present in roster** for the entire adjudication window
- APK on all participating devices matches §1 artifact identity

**Roster/topology not exercised → INCONCLUSIVE.**

---

## 3. Target fault shape (valid-run prerequisite)

The run MUST actually exercise this shape (17:02 class). Final GROUP READY alone is **not** Patch-2C PASS.

```text
M02 ↔ M04 required edge
+ signaling / delivery already occurred
+ edge not yet established
+ MEDIA_PENDING / equivalent causal evidence
+ CHECKING / missing-connected-edge shape
```

**INCONCLUSIVE** (not PASS, not FAIL) when any of:

- Four nodes connect naturally and the target shape never forms
- Layer 1 / membership first breaks the canonical roster so M02↔M04 is not a stable required edge
- Artifact or topology prerequisites fail

**Absence of recovery is not FAIL** until the target shape and valid preconditions are proven.

Operator records in `RUN_NOTES.txt` how the shape was induced (timing / accept order / churn). No mandated flap matrix.

---

## 4. Log collection

```text
Log bundle: talkback/logs/adr0065-appendix-b-<YYYYMMDD-HHMMSS>/
Primary adjudication node: M02 (offerer on target edge)
Corroboration (if USB available): M04
Supporting: M01 / M03 as available
Tag: Talkback:I
```

Minimum bundle:

- `RUN_NOTES.txt` (artifact + stimulus + operator shape attestation)
- `ARTIFACT.txt` (commit, APK, device list)
- `m02-logcat.txt` (required)
- `m04-logcat.txt` (recommended)
- Optional: `m01-logcat.txt`, `m03-logcat.txt`

---

## 5. Procedure (single run)

### Step 0 — Preflight

- [ ] Artifact identity recorded (§1)
- [ ] Four-node GROUP session path selected (not Meeting)
- [ ] Log collectors running on M02 (required)
- [ ] Roster confirmed M01+M02+M03+M04

### Step 1 — Baseline join

- [ ] Establish GROUP session with four-node roster
- [ ] Confirm non-target edges do not substitute for M02↔M04 exercise
- [ ] Note baseline time in `RUN_NOTES.txt`

### Step 2 — Induce target fault shape (one episode)

- [ ] Operator action produces **accept + edge not established / prolonged CHECKING** on **M02↔M04**
- [ ] Do **not** USER_LEAVE / roster amputation / membership edits
- [ ] Do **not** start a second shape episode in the same run

Reference only: pre-fix bundle `adr0065-sync-stuck-repro-20260918-1705/`.

### Step 3 — Passive self-heal observation window

After shape is attested:

- [ ] Wait **≥ 180 s** (covers default `checkingGraceMs` + dispatch + ICE convergence)
- [ ] No manual UI workaround for the target edge
- [ ] No app force-stop
- [ ] No second stimulus

### Step 4 — Stop collection

- [ ] Stop logcat collectors
- [ ] Seal bundle path in `RUN_NOTES.txt`
- [ ] Proceed to [adjudication](./adr0065-appendix-b-adjudication.md) — **read-only**

---

## 6. STOP rules (frozen)

| Event | Action |
|-------|--------|
| **PASS** adjudicated | **STOP** — do not confirm with a second run |
| **FAIL** adjudicated | **STOP** → this log bundle is the only RCA input — **no second field run** |
| **INCONCLUSIVE** | **STOP** the invalid run. Does **not** consume the verification slot. Operator **must not** retry in the field until the invalid reason is recorded and it is decided whether a new attempt is still inside this authorization |

---

## 7. Appendix B Review closure

```text
B1 Artifact identity       PASS
B2 Target fault shape      PASS
B3 Key chain evidence      PASS
B4 Verdict rules           PASS
B5 STOP rule               PASS

Appendix B Review          PASS
ONE FIELD VERIFICATION     AUTHORIZED
Authorized target:         M02 ↔ M04, one valid 17:02-class run
```

---

## One-line scope statement

> Appendix B: one M02↔M04 field run verifying Patch-2C production self-heal on the 17:02 accept-without-media shape; not desk re-qualification; not Meeting; not soak.
