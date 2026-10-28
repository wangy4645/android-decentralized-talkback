# RC-3 C1Q — Release DUT Evidence Transport Amendment

**Type:** qualification / operator tooling amendment  
**Not:** ADR-0058 product contract · Meeting architecture · Profile01 semantics change  
**Status:** **DESIGN PASS / IMPLEMENTATION PASS (mechanical)** (2026-09-16) · field rerun **NOT AUTHORIZED**

```text
Parent blocker:  PHASE_MINUS_1 = BLOCKED
                 RELEASE_DUT_EVIDENCE_EXTRACTION_INCOMPATIBLE
Field evidence:  talkback/logs/rc3-c1q-phase-minus1-20260916-104319/
Phase -1 card:   rc3-c1q-phase-minus1-run-card.md
```

---

## 1. Problem boundary (frozen)

### Formal ruling (2026-09-16)

```text
PHASE_MINUS_1 = BLOCKED

Blocker: RELEASE_DUT_EVIDENCE_EXTRACTION_INCOMPATIBLE

Product defect                 = NO
Provisioning mechanism failure = NOT ESTABLISHED
Qualification tooling gap      = YES

RC-3 / RC-4 / C1Q field        = NOT AUTHORIZED
```

### What Phase -1 proved (do not re-describe)

```text
provision action       → executable (instrumentation OK; EP_OP_PROBE outcome=READY)
instrumentation        → executable
release target         → unchanged (M02 rc.1 POST identity PASS)
evidence extraction    → incompatible (run-as on non-debuggable release)
authority batch        → cannot start (invalid host evidence file)
```

**Distinction (frozen):**

```text
Provisioning execution  ≠  Evidence extraction
Evidence generation     ≠  Evidence transport
EP_OP_PROBE READY       ≠  establishment provisioning PASS (旁证 only)
```

### Amendment scope

```text
IN SCOPE
  release non-debuggable → run-as evidence extraction gap
  evidence transport only (same payload semantics)
  A / B / C comparison
  source-read-only Q1–Q5
  preferred path recommendation

OUT OF SCOPE
  product behavior / Profile01 / trust-provisioning semantics
  evidence weakening / READY-as-PASS reinterpretation
  rc.1 target modification / debug target override / installProdDebug
  field execution / new qualification gate / C1Q field
  automatic Option C authorization
```

### Frozen principle

**不能降低证据语义，只能替换 evidence transport。**

```text
BEFORE (debuggable-era operator path)
  instrumentation
  → target-private artifact (EP_OPERATOR_EVIDENCE.txt / FTPH_RESULT.txt)
  → run-as pull
  → workstation authority batch / P-1C adjudication

AFTER (release DUT path — design target)
  instrumentation
  → SAME authoritative payload (byte- or semantic-equivalent)
  → instrumentation result / stdout transport
  → workstation writes same host filenames
  → authority-batch → accept-device → PRE_CHECK (semantics unchanged)
```

---

## 2. Transport options (A / B / C)

| Option | Core idea | Touches rc.1 release APK? | Evidence semantics | Posture |
|--------|-----------|----------------------------|--------------------|---------|
| **A. Instrumentation result transport** | At generation time (or via `Instrumentation.sendStatus` / `INSTRUMENTATION_RESULT` stream), emit the **same** key=value payload workstation currently obtains via `run-as cat` | **No** (androidTest + operator scripts only) | Preservable if payload is byte-equivalent to file content | **Preferred** |
| **B. Test-package-owned evidence readback** | After target-private write, androidTest reads `targetContext.filesDir/gbc-trust/*` and exports via instrumentation (no `run-as`; instrumentation already has `targetContext`) | **No** (androidTest + scripts) | Byte-equivalent to `run-as cat` if same file bytes | **Fallback** if A wiring is awkward |
| **C. Release-visible controlled export** | Production app adds export seam (ContentProvider, debug flag, etc.) | **Yes** → new artifact identity (normally rc.2) | Risk of widening production surface | **Last resort** — separate product/artifact decision |

Priority: **A > B >>> C**

### Option A — detail

**Mechanism (design, not implemented):**

1. Extend field-test instrumentation helpers (`EpEstablishmentOperatorProvisionSupport`, `FieldTestProvisioningSupport`) to emit authoritative payloads on the instrumentation result channel **in the same call** that today writes target-private files.
2. Replace `ep-establishment-operator-pull-evidence.ps1` input path: parse `INSTRUMENTATION_RESULT:` lines (or dedicated result keys) → write `Mxx-evidence.txt` / capture FTPH blob on host.
3. Leave `EstablishmentOperatorAuthorityFieldHarness.loadEvidence()` and downstream accept/PRE logic unchanged.

**Why preferred:** Same provisioning logic, same generation site, same host file contract; only transport changes. No second read from target-private state required (see Q2).

### Option B — detail

Add a field-test export test (e.g. `#exportTargetPrivateArtifact`) that reads an existing target-private file through `InstrumentationRegistry.getInstrumentation().targetContext` and returns contents via instrumentation result.

**Limitation vs A:** Requires round-trip write→read; still field-test only. Semantically equivalent to today's `run-as cat` but extra step.

**When useful:** Minimal change if generation sites must not be touched; or retroactive export of artifacts already written.

### Option C — detail

Any production-visible export changes signing surface, release audit, and frozen rc.1 identity. **Not recommended** for RC-3 C1Q unblock. If A/B cannot preserve semantics, **STOP** and escalate as separate rc.2 / product decision — do not auto-authorize C.

---

## 3. Source-read-only Q1–Q5

### Q1 — `EP_OPERATOR_EVIDENCE.txt` authoritative payload

**Generator:** `EpEstablishmentOperatorProvisionSupport.provisionKeystoreAndEmitEvidence()`  
(`talkback-app/src/androidTest/.../EpEstablishmentOperatorProvisionSupport.kt`)

**Not** production Factory code. Field-test operator helper invoked by `EpEstablishmentOperatorProvisionInstrumentedTest#provisionLocalEstablishmentIdentity`.

**Fields written (exact key=value lines):**

| Key | Source |
|-----|--------|
| `moduleId` | `EstablishmentEnrollmentEvidence.moduleId` |
| `algorithm` | `evidence.algorithm.name` |
| `publicKeyFingerprintSha256Hex` | enrollment evidence producer |
| `keyAlias` | enrollment evidence producer |
| `localKeyGeneration` | argument / evidence |
| `publicKeySpkiBase64` | `Base64.encodeToString(evidence.publicKeySpki)` |
| `operatorAssignedEstablishmentKeyVersion` | operator argument |

**Workstation consumer:** `EstablishmentOperatorAuthorityFieldHarness.loadEvidence()` reads `M01-evidence.txt` / `M02-evidence.txt` and requires: `moduleId`, `publicKeyFingerprintSha256Hex`, `keyAlias`, `localKeyGeneration`, `publicKeySpkiBase64`, `operatorAssignedEstablishmentKeyVersion`. The `algorithm` line is **written but not parsed** by harness (harness hardcodes `EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1` in `toEnrollmentEvidence()`).

**Authoritative for signing:** SPKI + fingerprint + alias + versions — not logcat `EP_OP_PROBE` (log omits `publicKeySpkiBase64`).

---

### Q2 — Are all fields available in instrumentation lifecycle before run-as?

**Yes — at generation time, in memory, before any run-as pull.**

In `provisionKeystoreAndEmitEvidence()` lines 67–85, `EstablishmentEnrollmentEvidenceProducer.produce(identity)` yields a complete `evidence` object; the `linkedMapOf(...)` is assembled **before** `File(trustDir, EVIDENCE_FILE).writeText(...)`.

No second read from target-private state is required to obtain the authoritative payload for workstation transport.

---

### Q3 — Byte-equivalent / semantic-equivalent via instrumentation result?

**Feasible (design); not implemented today.**

- Codebase has **no** existing `Instrumentation.sendStatus` / `INSTRUMENTATION_RESULT` usage (grep: none).
- The exact file body is deterministically built as `lines.entries.joinToString("\n") { "${it.key}=${it.value}" }` — this string can be emitted on the instrumentation result channel and written to host `Mxx-evidence.txt` **byte-for-byte**.
- Workstation `pull-evidence.ps1` currently accepts any content that does not match `No such file|not found|error`; release run-as failure produced invalid host file — transport bug, not semantics change.

**Option B alternative:** `targetContext` read of the written file would also be byte-equivalent to `run-as cat` (instrumentation has target app context without debuggable flag).

**Not sufficient alone:** logcat `EP_OP_PROBE ... outcome=READY` — missing `publicKeySpkiBase64` (required by authority harness).

---

### Q4 — Can authority-batch replace input transport only?

**Yes, for establishment evidence pull.**

`EstablishmentOperatorAuthorityFieldHarness.run()` reads host files only (`loadEvidence(File(batchDir, "M01-evidence.txt"))`). Signing, manifest generation, and delivery bytes are unchanged if host files are byte-equivalent.

**Downstream unchanged (if host inputs valid):**

```text
ep-establishment-operator-authority-batch.ps1
ep-establishment-operator-accept-device.ps1  (already instrumentation-driven for delivery)
ep-establishment-operator-pre-check.ps1        (see Q5 / PRE-1 gap)
```

**Operator script changes (tooling only):** replace `run-as cat` pull with instrumentation-result capture; do not alter harness verification rules.

---

### Q5 — FTPH / P-1C-2: same run-as wall?

**Yes — same amendment class; do not fix establishment only.**

#### FTPH artifact

**Generator:** `FieldTestProvisioningSupport.provision()` → `writeResultArtifact()`  
(`FieldTestProvisioningSupport.kt`)

**File:** `files/gbc-trust/FTPH_RESULT.txt`

**Payload includes (when success):**

```text
success, localModuleId, deploymentTrustDomainId,
establishmentRecordIdentity, gfSignerRecordIdentity, fingerprintMatch,
acceptedBinding.<moduleId>.signerKeyVersion,
acceptedBinding.<moduleId>.fingerprint,
runtimeSigner.<moduleId>.fingerprint,
runtimeSigner.<moduleId>.match,
message
```

Built from in-memory `FieldTestProvisioningResult` **before** file write — same Q2 pattern as establishment.

**Current tooling run-as dependency:**

| Location | run-as use |
|----------|------------|
| `adr0057-field-test-provision-device.ps1` | post-run `cat FTPH_RESULT.txt`, `ls gbc-trust/` (diagnostic) |
| `rc3-c1q-phase-minus1-run-card.md` P-1C-2 | **gate evidence**: `FTPH_RESULT.txt` content + `accepted-local-trust-state.bin` presence |

**Phase -1 field result:** FTPH instrument `OK (1 test)` on M01+M02; `run-as` blocked on release → cannot adjudicate full P-1C-2 as frozen.

#### P-1C-2 frozen interpretation (unchanged semantics)

```text
FTPH PASS + binding assertion (M01 @ signerKeyVersion=1) = trust evidence
accepted-local-trust-state.bin presence                 = persistence sanity only
```

Transport amendment must deliver **FTPH_RESULT-equivalent payload** (not merely instrument exit code). Instrument success alone is necessary but not sufficient for frozen P-1C-2 documentation.

#### PRE-1 second run-as wall (establishment batch)

`ep-establishment-operator-pre-check.ps1` PRE-1:

```powershell
run-as ... test -f files/gbc-trust/accepted-local-establishment-trust-state.bin
```

Also blocked on release. Same amendment should add instrumentation-based **store presence probe** (via `targetContext` file existence) — equivalent to PRE-1 boolean, not a weakened gate.

#### Accept-device diagnostics (non-blocking)

`ep-establishment-operator-accept-device.ps1` post-accept `run-as ls/cat` — diagnostic only; accept path already instrumentation-driven. Low priority vs evidence pull + PRE-1 + FTPH.

---

## 4. Recommendation

### Verdict: **A FEASIBLE → recommend A**

Source proof:

| Payload | All authoritative fields in instrumentation lifecycle? | Byte-equivalent transport possible? |
|---------|----------------------------------------------------------|-------------------------------------|
| `EP_OPERATOR_EVIDENCE.txt` | **Yes** (Q2) | **Yes** (Q3) |
| `FTPH_RESULT.txt` | **Yes** (`FieldTestProvisioningResult` before write) | **Yes** |
| PRE-1 store presence | N/A (boolean probe) | **Yes** via `targetContext` file existence in instrumentation |
| P-1C-2 `accepted-local-trust-state.bin` sanity | N/A | **Yes** — same probe pattern (sanity only per frozen card) |

**Option B:** Semantically sufficient as fallback (read back target-private files via `targetContext`) but strictly larger diff than A (extra test + read round-trip). Recommend only if A emission wiring is blocked for mechanical reasons.

**Option C:** **STOP** — not authorized. Would modify production release artifact.

### Minimal implementation surface (when separately authorized)

```text
androidTest field helpers
  emit EP_OPERATOR_EVIDENCE body on instrumentation result (A)
  emit FTPH_RESULT body on instrumentation result (A)
  optional: exportStorePresence probe for PRE-1 / P-1C-2 sanity (instrumentation)

operator scripts (no harness change)
  ep-establishment-operator-pull-evidence.ps1  → parse instrumentation result
  ep-establishment-operator-provision-device.ps1 → drop run-as dependency for evidence display
  adr0057-field-test-provision-device.ps1       → capture FTPH payload from instrumentation
  ep-establishment-operator-pre-check.ps1        → PRE-1 via instrumentation probe

frozen rc.1 release target APK                     → NO CHANGE
EstablishmentOperatorAuthorityFieldHarness         → NO CHANGE
Profile01 / trust contracts                        → NO CHANGE
```

### Phase -1 card follow-on — DONE (2026-09-16)

`rc3-c1q-phase-minus1-run-card.md` updated: P-1C-2 uses transported `FTPH_RESULT` + `probeSigningTrustStorePresent` STORE_SANITY. Phase -1 operator script path has **zero** `run-as` evidence extraction deps.

---

## Authorization ledger

```text
Amendment design              PASS / FROZEN
Option A                      SELECTED
Transport implementation      PASS (A1+A2+A3)
P-1C-2 STORE_SANITY alignment   PASS (2026-09-16)
Phase -1 run-card audit         PASS (zero run-as evidence deps)
Mechanical tests              PASS

Provisioning execution        NOT AUTHORIZED
Phase -1 rerun                NOT AUTHORIZED
C1Q field                     NOT AUTHORIZED
rc.1 release APK              UNCHANGED
Option C                      NOT AUTHORIZED
```
