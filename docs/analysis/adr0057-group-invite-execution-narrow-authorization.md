# ADR-0057 Track C — Group Invite Execution Narrow Authorization (F4 / GIE)

## Status

**AUTHORIZED — IMPLEMENTED — FIELD PASS / CLOSED** (2026-08-31)

```text
Finding F4:
  GROUP INVITE EXECUTION LIFECYCLE COUPLING
  CONFIRMED → CLOSED FOR THIS DEFECT

Classification:
  NARROW IMPLEMENTATION DEFECT

Architecture redesign:
  NOT REQUIRED

Implementation cut:
  AUTHORIZED → VERIFIED (field)

Required:
  GIE-IA-T1..T4
  GIE-EG1..EG6

Generation Convergence:
  FIELD PASS / FROZEN

ACQ:
  FIELD PASS / CLOSED

CSO:
  FIELD PASS / CLOSED FOR THIS DEFECT

Case A End-to-End Bootstrap:
  PARTIAL (downstream F5 inbound accept — see inbound audit)

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [Post-convergence group bootstrap residual audit](./adr0057-post-convergence-group-bootstrap-residual-audit.md)
- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)

---

## Frozen gap

```text
admission / eligibility PASS
        ↓
sendGroupMeshInvitesInternal / issuePairwiseMeshAdmissionInvite
        ↓
acquireMeshEngine (sync)
        ↓
FAIL — async release pending
        ↓
GROUP_INVITE never on wire
```

Control plane intent must survive media-engine deferral (same pattern as ACQ obligation).

---

## GIE-IA-T1 — Intent survives engine unavailability

Once invite admission/eligibility succeeds, synchronous engine acquisition failure **must not** discard the admitted intent.

```text
admitted invite intent
→ engine unavailable / release pending
→ DEFERRED_ENGINE_UNAVAILABLE
→ intent remains actionable
→ lifecycle reusable
→ retry execution opportunity
→ valid GROUP_INVITE
```

## GIE-IA-T2 — No malformed wire bypass

Do not fabricate, reuse stale, or emit incomplete `GROUP_INVITE` to avoid waiting. Valid offer only after engine provision completes.

## GIE-IA-T3 — Release ordering preserved

Pending media-engine release retains precedence. GIE must not force-overlap or bypass release solely to send `GROUP_INVITE`.

## GIE-IA-T4 — Intent / attempt / wire-send separation

Repeated late-peer reconciliation may observe the same open intent. Must not create uncontrolled duplicate wire sends. Deduplication suppresses duplicate attempts, not future opportunities after deferral.

---

## Minimum fixtures (GIE-EG1..EG6)

| Gate | Requirement |
|------|-------------|
| EG1 | admission PASS + engine available → valid GROUP_INVITE |
| EG2 | release pending → DEFERRED_ENGINE_UNAVAILABLE, zero malformed INVITE, intent retained |
| EG3 | release complete → execution opportunity → exactly one valid GROUP_INVITE |
| EG4 | repeated reconcile → no INVITE burst |
| EG5 | satisfied intent → no duplicate wire send on later lifecycle events |
| EG6 | ADR-0057 / release-ordering regression PASS |

`transmitReady` is **not** a GIE unit gate.

---

## Authorized scope

- Group invite execution lifecycle deferral
- Durable/open invite intent within runtime lifetime
- `DEFERRED_ENGINE_UNAVAILABLE` diagnostic
- Media-lifecycle-completion → execution opportunity (via existing `requestGroupEngine` queue)
- Attempt/send deduplication
- GIE-EG1..EG6

## Not authorized

ICE retry changes, transmitReady semantics, membership policy, LOCAL_NOT_BIDIRECTIONAL relaxation, generation/successor changes, CSO/ACQ/trust changes, native force-kill, release-ordering bypass, new topology.

---

## Sign-off

```text
☑ F4 audit complete
☑ GIE-IA-T1..T4 frozen
☑ Implementation cut authorized
☑ GIE-EG1..EG6 PASS (unit / regression)
☑ GIE FIELD PASS (Case A 20260831-163552)
```

### Field evidence (2026-08-31)

```text
LogDir: talkback/logs/adr0057-track-c-case-a-20260831-163552/
PRECONDITIONS: talkback/logs/adr0057-preconditions-20260831-163539/
Build: cac628d + GIE cut
F2: PASS (zero HELLO from M04)

GIE FIELD PASS:
  late peer / deferred create
  → release complete
  → MEDIA_PENDING_DRAIN
  → MEDIA_PROVISIONED
  → GIE_EXECUTION_OPPORTUNITY
  → GIE_GROUP_INVITE_WIRE_SENT
  → peer received GROUP_INVITE on wire

Authoritative M01→M03:
  16:37:38.981 GROUP_LATE_PEER_DISCOVERED peer=M03
  16:37:38.985 MEDIA_RELEASE_DEFERRED_CREATE / mediaSessionReuse
  16:37:39.008 MEDIA_RELEASE_COMPLETE → MEDIA_PENDING_DRAIN
  16:37:39.067 MEDIA_PROVISIONED + GIE_EXECUTION_OPPORTUNITY
  16:37:39.103 GIE_GROUP_INVITE_WIRE_SENT peer=M03
  M03 received GROUP_INVITE from M01 (and later M02)

Dedup: latePeer≃300 vs wireSent≃12 (M01); GIE_PROVISION_SKIPPED_DUPLICATE observed
GIE_DEFERRED_ENGINE_UNAVAILABLE: 0 this run (defer via provision queue, not close-inflight diagnostic)
transmitReady: NOT scored for GIE
```

### Implementation seam (2026-08-31)

| Component | Change |
|-----------|--------|
| `TalkbackCoordinator.dispatchGroupInviteAfterEngineProvision` | Async `requestGroupEngine` replaces sync `acquireMeshEngine`; `DEFERRED_ENGINE_UNAVAILABLE` when release in flight |
| `GroupInviteExecutionSupport` | Intent / provision / wire dedup gates |
| `MediaSessionManager.scheduleGroupReleaseInFlightPendingDrainAfterClose` | On `mediaSessionClose` complete, drain **GROUP + releaseInFlight** pending only — preserves CONFERENCE hangup stacking (RCA m03-host-pc-generation) |

### Fixture mapping

| Gate | Test |
|------|------|
| EG1 | `Adr0057GroupInviteExecutionFixturesTest.gieEg1_engineAvailable_emitsWireOnce` |
| EG2 | `gieEg2_releasePending_defersWithoutWire` |
| EG3 | `gieEg3_releaseComplete_grantsExecutionOpportunityExactlyOnce` |
| EG4 | `gieEg4_repeatedReconcile_suppressesDuplicateProvision` |
| EG5 | `gieEg5_wireInFlight_suppressesSecondSend` |
| EG6 | `Adr0057GbcAcceptanceFixturesTest` + `Adr0057ColdStartOriginFixturesTest` + `MediaSessionManagerAsyncReleaseBoundaryTest` |
