# ADR-0057 Track C — Inbound Group Invite Execution Narrow Authorization (F5 / IGIE)

## Status

**AUTHORIZED — IMPLEMENTATION CUT** (2026-08-31)

```text
Finding F5:
  INBOUND GROUP INVITE EXECUTION LIFECYCLE COUPLING
  CONFIRMED

Classification:
  NARROW IMPLEMENTATION DEFECT (symmetric to F4 / GIE)

Pattern:
  inbound GROUP accept depends on synchronous media-engine acquisition
  while async release is pending → accept obligation lost

Implementation cut:
  AUTHORIZED

Required:
  IGIE-IA-T1..T4
  IGIE-EG1..EG6

F4 / Outbound GIE:
  FIELD PASS / CLOSED

Generation Convergence:
  FIELD PASS / FROZEN

Case A End-to-End:
  PARTIAL / OPEN

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [Inbound residual audit](./adr0057-inbound-group-invite-execution-residual-audit.md)
- [Outbound GIE authorization](./adr0057-group-invite-execution-narrow-authorization.md)
- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)

---

## Frozen gap

```text
valid GROUP_INVITE received
→ acceptGroupInvite / acceptGroupInviteReconnect entered
→ sync acquireMeshEngine / getOrCreateMeshEngine throws
→ accept intent lost
→ engine later ready
→ zero GROUP_ACCEPT / zero SRD
```

---

## IGIE-IA-T1 — Accept obligation survives engine deferral

Once a valid inbound `GROUP_INVITE` has passed admission and accept processing has started, temporary engine unavailability **must not** discard the accept obligation.

Session/channel-mode side effects happen **once** before async engine provision; resume must **not** re-run full `acceptGroupInvite()`.

## IGIE-IA-T2 — SDP/application ordering

Remote offer application and `GROUP_ACCEPT` generation **only** with a ready engine. No partial SRD, stale reuse, fabricated answer, or premature `GROUP_ACCEPT`.

## IGIE-IA-T3 — Release ordering preserved

Pending release retains precedence. IGIE must not force synchronous overlap, cancel release, or bypass `MediaSessionManager` ownership.

## IGIE-IA-T4 — Intent / attempt / completion separation

Duplicate inbound invites may observe the same open accept obligation. Must not create `GROUP_ACCEPT` bursts, parallel accept transactions, or permanently suppress resume after deferral.

---

## Minimum fixtures (IGIE-EG1..EG6)

| Gate | Requirement |
|------|-------------|
| EG1 | engine ready → remote offer applied → exactly one valid `GROUP_ACCEPT` |
| EG2 | release pending → deferred, zero partial SRD / malformed accept, obligation retained |
| EG3 | release complete → resume → SRD succeeds → exactly one `GROUP_ACCEPT` |
| EG4 | duplicate invite while OPEN → no parallel accept / burst |
| EG5 | accept satisfied → duplicate/lifecycle event → no second accept |
| EG6 | GROUP release ordering + GIE + CSO/ACQ/GBC regressions PASS |

`transmitReady` is **not** an IGIE unit gate.

---

## Authorized scope

- GROUP inbound async engine-ready seam (`beginGroupAcceptWhenEngineReady` / `completeGroupAcceptWithEngine`)
- Fresh accept + reconnect accept paths
- Retained inbound accept context (immutable after admit — no duplicate session/`enterChannelMode`)
- Engine-ready resume + accept deduplication
- IGIE diagnostics
- IGIE-EG1..EG6

## Not authorized

ICE retry, `transmitReady` semantics, membership policy, `waitingForPrimary` policy, generation/CSO/ACQ changes, outbound GIE reopening, release bypass / native kill, Conference semantic changes.

---

## Field gate (Case A re-run)

```text
M03 receives GROUP_INVITE
→ deferred if needed
→ engine ready
→ remote offer applied (SRD)
→ GROUP_ACCEPT on wire
```

---

## Sign-off

```text
☑ F5 audit complete
☑ IGIE-IA-T1..T4 frozen
☑ Implementation cut authorized
☑ IGIE-EG1..EG6 PASS (unit / regression)
☑ IGIE FIELD CONFORMANCE PASS (answerer M02, Case A 20260831-165729)
□ M03 late-peer INVITE delivery (separate residual — not IGIE accept fail)
```

### Field evidence (2026-08-31)

```text
LogDir: talkback/logs/adr0057-track-c-case-a-20260831-165729/
PRECONDITIONS: talkback/logs/adr0057-preconditions-20260831-165718/
Build: cac628d + IGIE cut
F2: PASS (zero HELLO from M04)
Synchronous mesh engine create pending: 0 (all nodes)

IGIE FIELD CONFORMANCE (M02 answerer — authoritative):
  16:58:21.351  GROUP_INVITE received from M01 (2040B)
  16:58:21.381  acceptGroupInvite → CHANNEL_MODE GROUP_PTT
  16:58:21.526  MEDIA_PROVISIONED module=M01 (async requestEngine)
  16:58:21.551  IGIE_EXECUTION_OPPORTUNITY path=Fresh origin=engine_ready
  16:58:21.666  GROUP_ACCEPT_HANDOFF path=INVITE SUCCESS settled=true
  16:58:21.668  IGIE_GROUP_ACCEPT_WIRE_SENT peer=M01
  16:58:21.682  Group invite accepted
  M01 received GROUP_ACCEPT from M02
  Duplicate invites → IGIE_PROVISION_SKIPPED_DUPLICATE gate=ACCEPT_COMPLETE

M03 Case A late-peer gate:
  M01/M02 sent GROUP_INVITE to M03 (wire SENT)
  M03 RECEIVED: 0 GROUP_INVITE (only HELLO/HEARTBEAT/FACT_*)
  → INVITE delivery miss during M03 cold-start socket rebind
  → NOT scored as IGIE accept-lifecycle FAIL
  → separate residual (delivery / late-peer invite reachability)

transmitReady: NOT scored for IGIE
```

### Implementation seam (2026-08-31)

| Component | Change |
|-----------|--------|
| `TalkbackCoordinator.beginGroupAcceptWhenEngineReady` | Async `requestGroupEngine`; session admit once; resume via `completeGroupAcceptWithEngine` |
| `TalkbackCoordinator.completeGroupAcceptWithEngine` | SRD + `GROUP_ACCEPT` only after engine ready (Fresh + Reconnect paths) |
| `GroupInviteInboundExecutionSupport` | Accept obligation / provision / completion dedup gates |
| `GroupInviteInboundExecutionLog` | `IGIE_*` diagnostics |

### Fixture mapping

| Gate | Test |
|------|------|
| EG1 | `Adr0057GroupInviteInboundExecutionFixturesTest.igieEg1_engineAvailable_emitsAcceptOnce` |
| EG2 | `igieEg2_releasePending_defersWithoutAccept` |
| EG3 | `igieEg3_releaseComplete_grantsExecutionOpportunityExactlyOnce` |
| EG4 | `igieEg4_repeatedInvite_suppressesDuplicateProvision` |
| EG5 | `igieEg5_acceptComplete_suppressesSecondAccept` |
| EG6 | `Adr0057GroupInviteExecutionFixturesTest` + `Adr0057GbcAcceptanceFixturesTest` + `MediaSessionManagerAsyncReleaseBoundaryTest` |
