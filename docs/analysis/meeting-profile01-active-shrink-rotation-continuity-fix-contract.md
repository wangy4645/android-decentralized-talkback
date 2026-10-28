# Meeting Profile01 — ACTIVE Shrink Rotation Continuity Fix Contract

**Parent ADR:** `talkback/docs/adr/0017-transition-declaration-and-media-lifecycle.md`  
**RCA basis:** `talkback/docs/analysis/meeting-epoch3-field-setup-blocker-rca.md` (R0/R1 appendices)  
**Reproduction:** R2 desk — `MeetingProfile01ActiveShrinkRotationDeskTest` · IG-2 harness extensions

**Status:** **ACCEPTED — CONTRACT ONLY**  
**Implementation:** **NOT AUTHORIZED**  
**Field verification:** **NOT AUTHORIZED**

```text
R0 differential                    CLOSED
R1 shrink RCA                      CLOSED
R2 ACTIVE-shrink desk reproduction CLOSED
Root cause                         CONFIRMED

Fix contract                       AUTHORIZED (this document)
Production implementation          NOT AUTHORIZED
Field rerun                        NOT AUTHORIZED
Fence relaxation                   NOT AUTHORIZED
Issue A (epoch2 audio quality)     PAUSED / SEPARATE / OUT OF SCOPE
EG-1 field closure                 HOLD (separate track)
```

**Authorizes:** narrow lifecycle-ordering contract for **ACTIVE-session membership shrink**
(authoritative roster removal during live multicast) — continuity obligation only.

**Does NOT authorize:** implementation · unit/desk execution beyond existing R2 fixture ·
field rerun · fence relaxation · generic rotation framework · invite/expiry policy ·
M04 establishment provisioning · subset-create · EG-1 field procedure · Issue A RCA/fix.

---

## 1. Frozen root cause (CONFIRMED)

```text
ACTIVE membership shrink rotation:

  session head advances to epoch N+1
  (sessionControl / supplement materialization @ N+1)

  successor member binding NOT YET installable
  (factPort.memberBinding incoherent with new session head)

  → coherent epoch-N TX binding is destroyed or unreachable
  → NO_MEMBER_BINDING window

Field (BAD #2): window did NOT complete recovery
  → SOURCE_GENERATION_MISMATCH downstream
  → sustained SHADOW_TX_FENCED
  → silence

Desk (R2): same ordering gap reproducible at package-drain boundary;
  synchronous replay can heal — field did not.
```

**Root-cause class:** `rotation lifecycle ordering gap` (Model C) — **not** fence defect.

**First break (field, frozen):** M01 host @ `16:36:11.713` — `NO_MEMBER_BINDING` while
MULTICAST TX still ACTIVE on epoch2 catalog after shrink membership projection.

**Not root cause:** M04 `UNKNOWN_ESTABLISHMENT_MODULE` alone (present in GOOD and BAD runs);
EG-1 same-cardinality epoch advance desk PASS; E3-A STAGED package lifecycle (separate contract).

---

## 2. Core invariant — `ACTIVE_ROTATION_CONTINUITY`

```text
ACTIVE_ROTATION_CONTINUITY

Given:
  TX is coherent and READY at mediaKeyEpoch N
  (ig2ResolveTxFenceReason / production resolveEgressContext returns null)

When:
  authoritative membership advances to mediaKeyEpoch N+1
  due to membership SHRINK or equivalent roster removal
  during an ACTIVE multicast session

Then:
  runtime MUST NOT enter a sustainable state where
    sessionControl = N+1
    AND no coherent TX binding is available
  merely because successor materialization is incomplete.

Equivalently:
  MUST NOT break epoch-N coherent binding before epoch-(N+1)
  successor binding is ready for TX use.
```

### 2.1 Allowed continuity models (fix shape — pick one at impl time)

| Model | Obligation |
|-------|------------|
| **A — make-before-break** | Retain coherent epoch-N media path until epoch-(N+1) successor binding + local source commitment are jointly ready; then atomic TX cutover; retire epoch-N. |
| **B — break-before-make (explicit pause)** | Explicitly suspend TX at rotation start; resume only when coherent epoch-(N+1) state is complete. Brief media pause is allowed. |

Contract does **not** mandate A over B. Either satisfies `ACTIVE_ROTATION_CONTINUITY`.

### 2.2 Forbidden model (Model C — must eliminate)

```text
C — implicit break-while-operating (FORBIDDEN)

  session / catalog advances toward N+1
  epoch-N binding disappears or becomes incoherent
  ordinary TX continues attempting egress
  → NO_MEMBER_BINDING / SOURCE_GENERATION_MISMATCH / epoch mismatch
  → indefinite fence / session collapse
```

---

## 3. Fence semantics — frozen (NO RELAXATION)

The following production TX fences **retain current safety semantics**.
Fix MUST NOT weaken, bypass, or special-case them for shrink rotation:

```text
NO_MEMBER_BINDING
SOURCE_GENERATION_MISMATCH
MEDIA_KEY_EPOCH_MISMATCH
REGISTRY_EPOCH_MISMATCH
```

**Correct posture:** fences block incoherent media. Fix restores rotation lifecycle so
coherent binding is available **before** or **instead of** operating in incoherent state —
not by redefining fence truth.

---

## 4. Scope boundary

### 4.1 In scope

- ACTIVE-session **membership shrink** rotation (e.g. authoritative `4→3` roster removal)
- Production ordering already evidenced: staged package wire → membership ingress →
  supplement drain / session materialize @ N+1 → member binding / source succession
- Shadow TX egress continuity through the transition
- Host and peer nodes under same rotation contract

### 4.2 Explicitly OUT OF SCOPE

| Item | Disposition |
|------|-------------|
| **Issue A** — epoch2 ~27s audible but poor quality (quiet + noise) | **PAUSED / SEPARATE** — must not merge into this fix |
| M04 establishment provisioning / `UNKNOWN_ESTABLISHMENT_MODULE` | Separate ops/design slice |
| Invite expiry policy / operator procedure | Not changed by this contract |
| Subset-create / channel remote filtering | Not changed by this contract |
| EG-1 field procedure / ExistingPublishedSession desk closure | **HOLD** — separate track |
| E3-A STAGED package lifecycle (expansion / late-join) | Covered by `meeting-profile01-epoch3-staged-package-lifecycle-fix-contract.md` |
| Generic rotation framework for all epoch advances | Rejected — shrink-on-ACTIVE only |
| Membership / completion admission / WiFi recovery domains | Frozen per workspace posture |

---

## 5. Completion owner (contract sketch — impl not authorized)

**Problem locus (evidence-aligned):** gap between

1. session head / supplement materialization @ N+1 (`tryMaterializeSessionAtMembershipHead`,
   `onSupplementReadyForConference`, host membership projection), and
2. successor member binding install + local source commitment visible to
   `resolveEgressContext` / `memberBinding`.

**Contract obligation:** a single rotation transaction MUST either

- complete A or B above before TX encounters `sessionControl=N+1` without binding, or
- hold session registry @ N until successor is ready (make-before-break variant).

**Does NOT prescribe:** specific class names, hook placement, or cross-node sync protocol —
only the continuity invariant and forbidden Model C.

---

## 6. Acceptance fixtures (mandatory before impl authorization)

Desk / unit fixtures MUST cover at minimum:

| ID | Obligation |
|----|------------|
| **F1** | Four-member epoch2 ACTIVE session; local TX `READY` (`fence=null`) before shrink |
| **F2** | Authoritative shrink `4→3` with production wire ordering (package → membership → drain → replay) |
| **F3** | Transition MUST NOT produce **sustainable** Model-C incoherent state (`session@N+1` + no coherent TX binding while TX armed) |
| **F4** | Successor epoch-(N+1) binding installed; sessionControl / registry / binding / local commitment reference same incarnation |
| **F5** | Post-rotation TX `READY` at epoch-(N+1) |
| **F6** | Removed member (M04) absent from authoritative membership head and media bindings |
| **F7** | Duplicate membership / package / supplement replay idempotent |
| **F8** | Existing EG-1 expansion desk + initial bootstrap regressions **PASS** unchanged |

**R2 baseline (already PASS for reproduction, not yet for F3 fix):**

- `MeetingProfile01ActiveShrinkRotationDeskTest.r2_activeShrinkRotation_documentsModelCLifecycleGap`
- Proves Model C gap at `after_shrink_package_drain` → `NO_MEMBER_BINDING` when successor
  materialization deferred (observability seam only)

**Post-fix:** same fixture family MUST flip F3/F5 to PASS without fence relaxation.

---

## 7. Field completion criteria (for future authorization only)

When field rerun is separately authorized, PASS requires:

```text
Pre:   MULTICAST_ACTIVE @ epoch N with ≥3 audible members
Event: authoritative shrink (member removed from roster)
Post:  remaining members recover TX READY @ epoch N+1 without sustained SHADOW_TX_FENCED
       removed member absent from authoritative state
```

**NOT required in field PASS:** Issue A audio quality judgment · M04 invite accept.

---

## 8. References

**RCA / evidence:**

- `talkback/docs/analysis/meeting-epoch3-field-setup-blocker-rca.md`
- BAD `#2`: `talkback/logs/epoch3-eg1-field-20260920-163328/`
- GOOD comparator: `talkback/logs/meeting-field-test-20260920-135135/`

**Desk tests:**

- `talkback/android-board-talkback/src/test/java/com/talkback/core/conference/session/integration/MeetingProfile01ActiveShrinkRotationDeskTest.kt`
- `talkback/android-board-talkback/src/test/java/com/talkback/core/conference/session/integration/MeetingProfile01E3AIG2FullChainDeskTest.kt` (IG-2 harness)

**Related contracts (do not merge):**

- `talkback/docs/analysis/meeting-profile01-epoch3-staged-package-lifecycle-fix-contract.md`
- `talkback/docs/analysis/meeting-profile01-peer-existing-session-endpoint-authority-fix-contract-amendment.md`

**Production fence reference:**

- `Profile01ShadowMulticastTransmitSeam.resolveEgressContext`

---

## 9. Status ledger

```text
R0/R1/R2 RCA + reproduction     CLOSED
Root cause                       CONFIRMED (§1)
Fix contract                     AUTHORIZED (§2–§6)
Production implementation        NOT AUTHORIZED
Field rerun                      NOT AUTHORIZED
Issue A                          PAUSED / SEPARATE
EG-1 closure                     HOLD
```
