# ADR-0057 Track C — Cold-Start Origin Production Seam Audit

## Status

**READ-ONLY AUDIT** (2026-08-31)  
Field trigger: [Track C Case A adjudication](./adr0057-track-c-case-a-field-adjudication.md) — F3 CONFIRMED

```text
Track C Finding F3 — CONFIRMED
  Cold-Start Generation Origination Gap

ACQ repair:           FIELD PASS — do not reopen
Trust / crypto / VB:  PASS / FROZEN — do not reopen
Next:                 origin production seam audit only (this document)
Implementation:       NOT AUTHORIZED by this audit
```

**Parents:**

- [GBC Contract Q1/Q2](../adr/0057-group-bootstrap-convergence-contract.md) — **FROZEN**
- [ACQ send-on-locate repair](./adr0057-acquisition-send-on-locate-wiring-repair.md) — **FIELD PASS**
- [FTPH narrow spec](./adr0057-field-test-provisioning-helper-narrow-spec.md) — signer persisted; origin emission explicitly out of scope

---

## Frozen field gap

```text
bootstrap candidate exists / can be resolved (M01)
        ↓
all participants UNKNOWN
        ↓
acquisition proves "nobody has Current" (INSUFFICIENT)
        ↓
[missing production action]
        ↓
originate first Current Generation Fact
```

`FACT_RESPONSE_INSUFFICIENT` is **valid cold-start evidence**. The defect is the
absence of a production bridge from that evidence to **Q1/Q2-qualified origin** on
the bootstrap-candidate Module only.

---

## Audit questions (four)

| # | Question | Finding |
|---|----------|---------|
| A | Who can call PV-2 origin issuance in production today? | **Nobody** — library exists; Coordinator unwired |
| B | Is there a bootstrap-candidate → origin-eligibility production bridge? | **No** |
| C | How is first generation identity allocated / persisted? | **Only in test fixtures** (`genesisAuthority("G*")`) |
| D | After origin, does Fact enter retained Current + re-provide? | **Path exists for verified ingress**; origin never feeds it |

---

## Seam chain (expected vs actual)

### Expected (frozen Q1/Q2)

```text
bootstrap candidate resolution (ChannelMeshHostElection / ADR-0053)
  → GBC: no legal Current known
  → origin eligibility decision (candidate only)
  → generation identity allocation
  → AuthoritativeGenerationFact construction
  → PV-2 issuance transaction (prepare → sign → finalize → publish gate)
  → local FactStore.retain + verification material
  → FACT_RESPONSE_CANDIDATE / broadcast
  → peers: normal VB → F1 → convergence
```

### Actual (production runtime)

```text
bootstrap candidate resolution          ✓ wired (Coordinator)
  → GBC acquisition OPEN                ✓ wired
  → FACT_REQUEST / INSUFFICIENT         ✓ wired
  → GBC onFactResponse(Insufficient)    ✓ wired (obligation stays OPEN)
  → origin eligibility                  ✗ NOT IMPLEMENTED
  → PV-2 origin emission                ✗ NOT WIRED
  → FactStore retain from origin          ✗ never reached
```

---

## 1. Bootstrap candidate resolution

**Wired.** Coordinator resolves primary via `ChannelMeshHostElection`:

```16390:16396:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun resolveBootstrapPrimary(members: Collection<ModuleId>): ModuleId? {
        if (members.isEmpty()) return null
        return ChannelMeshHostElection.electHost(
            localModuleId,
            members.filter { it != localModuleId }.map { it.value },
            emptyMap()
        )
    }
```

`GroupChannelAuthority` treats `bootstrapCandidate == localModuleId` as
authority-holder for **ADR-0053 control-plane** bootstrap emission
(`mayEmitBootstrap`), distinct from generation origin.

Field run: M01 elected primary; M02/M03 `waitingForPrimary=true`; M01 created
`GROUP_SESSION_CREATE currentMembers=M01,M02`.

**Gap:** bootstrap primary / session creation is **not** coupled to generation
origin. Primary can bootstrap GROUP mesh while GBC remains UNKNOWN with OPEN
obligation.

---

## 2. GBC local knowledge / acquisition result

**Wired for acquisition; no origin egress.**

`GroupBootstrapConvergence.onFactResponse(Insufficient)`:

```97:104:talkback/android-board-talkback/src/main/java/com/talkback/core/session/gbc/GroupBootstrapConvergence.kt
            is FactResponseOutcome.Insufficient -> {
                // ...
                obligations.onInsufficientOrAttemptTerminal()
                // M7: never regress accepted Current / reopen solely from INSUFFICIENT
                return emptyList()
            }
```

Effects: **empty**. Obligation stays OPEN. No `RequestFact` re-fire from
INSUFFICIENT alone (by design — prevents retry-as-progress).

`isOriginateForbiddenWhileUnknown()` returns true when UNKNOWN + OPEN + no
Current — but **nothing in production calls an originate path** when this becomes
false-eligible under Q2.

`onNoReachableHolderOrAttemptTerminal()` also returns empty effects while OPEN —
L5 frozen.

**Gap:** GBC correctly records INSUFFICIENT without closing obligation, but
exposes **no ConvergenceEffect** for "origin now eligible on bootstrap candidate".
Origin is not a GBC semantic change — but production needs an **observation →
eligibility → issuance** bridge outside forbidden shortcuts.

---

## 3. INSUFFICIENT wire path (holder side)

**Wired.** `handleGenerationFactRequest` → `onAuthorizedReprovideRequest`:

```7454:7507:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        when (val reprovide = factDeliveryOrchestrator.onAuthorizedReprovideRequest(payload.channelId)) {
            is ReprovideResult.Fact -> { /* FACT_RESPONSE_CANDIDATE */ }
            ReprovideResult.Insufficient -> { /* FACT_RESPONSE_INSUFFICIENT */ }
        }
```

`FactStore.reProvideCurrent()` returns null when no retained Current — correct.

Field: M01 sent INSUFFICIENT to M02 and M03 (no CANDIDATE).

---

## 4. INSUFFICIENT consumer path (requester side)

**Wired to GBC bookkeeping only.**

```7551:7565:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun handleGenerationFactResponseInsufficient(...) {
        factDeliveryOrchestrator.onInsufficientResponse(...)
        log("FACT_RESPONSE_INSUFFICIENT ch=... from=...")
    }
```

**No subsequent branch** for:
- bootstrap-candidate check
- origin eligibility
- PV-2 prepare/sign
- local verified Fact ingress

This is the **primary missing production seam** after ACQ repair.

---

## 5. PV-2 origin issuance — who can call it?

### Library (exists, test-only wiring)

| Component | Location | Production use |
|-----------|----------|----------------|
| `GenerationFactOriginEmissionSeam` | `issuance/GenerationFactOriginEmissionSeam.kt` | **None** |
| `GenerationFactIssuanceWriter` | `issuance/GenerationFactIssuanceWriter.kt` | **None** |
| `GenerationFactIssuancePublishGate` | same | **None** |
| `PersistedGenerationFactIssuanceSigner` | `issuance/PersistedGenerationFactIssuanceSigner.kt` | Loaded only via FTPH persistence path; **not composed into Coordinator** |
| `FileGenerationFactSignerPersistence.loadSigner()` | `issuance/FileGenerationFactSignerPersistence.kt` | **Not called from TalkbackRuntimeFactory** |

`TalkbackRuntimeFactory` composes **verification only**:

```209:221:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackRuntimeFactory.kt
        val resolvedGbcTrustWiring =
            gbcTrustWiring ?: run {
                val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
                GbcProductionTrustComposition.createProductionWiring(...)
            }
```

No `GenerationFactIssuanceWriter`, `GenerationFactOriginEmissionSeam`, or
`PersistedGenerationFactIssuanceSigner` in runtime factory.

`GbcTrustWiring` exposes only `verificationBoundary` + `factDeliveryOrchestrator`.

**Conclusion:** PV-2 origin issuance is **CLOSED / PASS in fixtures** but **zero
production callers**. FTPH established the signing key; origin emission remains
unwired (previously noted in FTPH spec §1.1).

---

## 6. Generation identity allocation

**Not implemented in production.**

Tests use `GenerationFactPv1FixtureSupport.genesisAuthority("G1")` with explicit
string identities. `GenerationFactIssuanceKey` is `(moduleId, signerKeyVersion)` —
**not** generation lineage identity.

No production allocator for:
- first generation identity (e.g. G1 / channel-scoped id)
- predecessor = null for genesis
- durable lineage cursor per channel

**Gap:** even if Coordinator called `prepareIssuance`, **generation identity
selection policy** is not present in production code.

---

## 7. Post-origin retain / re-provide

**Partially wired (ingress only).**

Verified Fact ingress path:

```text
VB SUCCESS → FactDeliveryOrchestrator.onCandidateResponse
  → wiring.onFactResponse(Fact)
  → GroupBootstrapConvergence.acceptVerifiedFact
  → FactStore.retain + obligation close effects
```

Origin would need to either:
1. Enter as **already-verified** `acceptVerifiedFact` (local origin authority), or
2. Emit CANDIDATE on wire and verify locally like any peer

`handleGenerationFactRequest` re-provide path would work **after** retain — but
origin never populates `FactStore`.

---

## 8. Coupling risks (audit guardrails)

### Must NOT implement (user-frozen)

```text
INSUFFICIENT from any peer → immediate originate
UNKNOWN alone → originate
timeout / waitingForPrimary → originate
three followers each originate G1
```

### Required tightening for next implementation cut

```text
Origin eligibility =
  localModuleId == bootstrap candidate (Q1)
  AND no legal Current in GBC snapshot (Q2 cold-start branch)
  AND explicit origin transaction (PV-2) — not HELLO / not INSUFFICIENT handler on followers
```

Single-writer: only **one** Module (bootstrap candidate) may originate first Current.
Followers must remain acquisition-only until CANDIDATE arrives.

---

## 9. Classification

```text
F3 — Cold-Start Generation Origination Gap

Type:        MISSING PRODUCTION SEAM (not GBC semantic defect)
Sub-type:    PV-2 origin unwired + no bootstrap-candidate → origin eligibility bridge
Blocked:     Track C generation convergence PASS
Unblocked:   ACQ repair (FIELD PASS)
```

| Layer | Implicated? |
|-------|-------------|
| ACQ wiring | No |
| Wire transport | No (FACT_* delivered) |
| Trust / FTPH | No (signer present) |
| VerificationBoundary | No (no candidate to verify) |
| F1 / GBC consumption | No (no Fact ingress) |
| **Origin production seam** | **Yes** |
| Group bootstrap / media | Downstream only |

---

## 10. Recommended next step (audit only — no code)

Before any implementation authorization:

1. **Map exact origin trigger observation** — what Coordinator event aggregates
   "candidate + OPEN obligation + acquisition proved no Current" without using
   INSUFFICIENT-per-peer as trigger.
2. **Define generation identity genesis policy** — channel-scoped allocator aligned
   with PV-1/PV-2 fixtures.
3. **Specify Coordinator composition** — wire `PersistedGenerationFactIssuanceSigner`
   + `GenerationFactOriginEmissionSeam` at `TalkbackRuntimeFactory` (mirror PW).
4. **Specify post-origin path** — local retain → optional CANDIDATE to requesters /
   re-provide readiness.
5. **Fixture set** — cold-start origin EG (single origin, follower no burst, M01
   only originates, INSUFFICIENT does not trigger followers).

**Do not** bundle with ACQ changes. **Do not** reopen trust/crypto.

---

## Sign-off

```text
☑ F3 gap confirmed from field evidence
☑ Four audit questions answered
☑ Missing seam localized (origin unwired + no eligibility bridge)
☑ Forbidden shortcuts documented
□ Implementation authorization (separate cut — NOT STARTED)
□ Track C Case A PASS
```
