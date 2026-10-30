# ADR-0060: Conference Media Transport Resilience Contract

## Status

**ACCEPTED — FROZEN** (2026-09-03)

Contract completion amendment: receiver/sender authority split, publication freshness
(defer epoch semantics), G-RES-1/3 envelope closure. Implementation **NOT AUTHORIZED**
by this ADR alone.

```text
Frozen:
  multicast-only as correctness foundation     REJECTED
  multicast as optimization path               ACCEPTED (subject to continuous liveness)
  correctness path                             sender-side unicast fan-out
  liveness semantics                           continuous; mid-session downgrade REQUIRED
  authority split                              receiver owns observation;
                                               sender owns delivery-mode execution
  V1 replication model                         sender-side only; NO relay / owner topology
  N_max                                        = 9 (G-RES-3-C1 FROZEN; see 0060-gres3-c1)
```

**Parents / boundaries:**

- [ADR-0058](./0058-conference-multicast-media-group.md) — Conference multicast media group contract **remains** for the optimization path and wire/runtime semantics. This ADR **does not** reopen Profile 01/02/03 frozen clauses except where explicitly amended below.
- [ADR-0059](./0059-underlay-multicast-product-assumption.md) — **AMENDED IN ROLE** (see §Amendment to ADR-0059). Underlay multicast is no longer a correctness dependency for Conference media delivery.
- E3a underlay probe chain — evidence basis; see `docs/analysis/adr0058-e3a-underlay-multicast-status.md`.
- WiFi recovery main chain — **CLOSED.** Do not merge.
- ADR-0056 deletion — **still HOLD** until replacement correctness path + capacity evidence pass.

**Does NOT authorize:**

```text
Media Engine / Opus / AudioTrack / Meeting integration cutover
MTK vendor workaround as correctness substitute
relay / elected replicator / new authority topology
completion / membership / RNA domain changes
```

---

## Context

ADR-0058 E3a underlay probes established that multicast reachability on the target Android device set is **not a stable correctness foundation**:

```text
E3a.3b  M02 ingress can establish then become non-persistent (~12s window → terminal 0%)
E3a.3c  AP/mesh reboot + forced reassoc did not restore M03→M02 ingress
E3a.3d  kernel IGMP present while app RX=0; MTK RX/filter path anomaly — HIGH
E3a.3e  A1 power_save off NO_CHANGE; A2 ALLMULTI on NO_CHANGE
```

Probe code already holds `WifiManager.MulticastLock` for the transport scope (`AndroidWifiMulticastLockSeam`). Failures therefore are **not** explained by the common "app forgot MulticastLock" class.

The investigation succeeded: it surfaced underlay risk **before** Media Engine investment. The failing assumption is architectural, not procedural:

```text
REJECTED:
  "multicast is the sole, reliable Conference media correctness path
   across the supported Android device set."
```

Consumer WiFi multicast correctness depends on a product-uncontrolled stack:

```text
AP IGMP snooping / per-client forwarding
multicast-to-unicast conversion behavior
DTIM / power-save scheduling
vendor driver RX packet-filter programming
```

Field evidence on one device cannot prove reliability for all future devices. A per-device MTK workaround, even if found, would only expand optimization coverage — it would **not** restore multicast-only correctness.

---

## Decision 1 — Transport role taxonomy

Conference media transport is split into two roles. They are **not** interchangeable.

### Correctness path (MUST work)

```text
sender-side unicast fan-out
```

Every admitted receiver MUST be able to receive Conference media over unicast when multicast optimization is unavailable or has failed.

### Optimization path (MAY be used)

```text
underlay multicast group delivery
```

Multicast MAY be selected only when the sender has consumed **receiver-published**
continuous liveness evidence showing multicast receive is **currently alive** for that
receiver (see Decision 2).

### Forbidden

```text
Conference correctness depending solely on multicast delivery
session-start-only multicast qualification with no mid-session watchdog
implicit "if joinSucceeded then forever multicast" routing
```

ADR-0058 multicast endpoint, RTP wire, and local mix model **remain valid** on the optimization path. This ADR adds a parallel correctness delivery mode; it does not delete multicast V1 design.

---

## Decision 2 — Multicast receive liveness (receiver-local observation)

Multicast qualification is a **runtime liveness contract**, not an admission stamp.
**Multicast ingress liveness is a receiver-local fact.** Only the receiving Module can
observe whether it is continuously receiving multicast media. A sender MUST NOT infer a
remote receiver's multicast RX liveness from its own multicast send state.

### Frozen authority split

```text
receiver owns observation
sender owns delivery-mode execution
```

A sender chooses `PerReceiverTransportMode` only after consuming remote
`MulticastReceiveLiveness` facts published by each receiver. These are **not** the same
authority. Conflicting dual ownership is forbidden:

```text
FORBIDDEN:
  M02 publishes MulticastReceiveLiveness = DEAD
  while M01 continues PerReceiverTransportMode = MULTICAST for M02
    without an explicit, evidenced sender mode transition
```

### Data flow (frozen)

```text
Receiver-local observation
    ↓
MulticastReceiveLiveness
    ALIVE | SUSPECT | DEAD
    ↓ publish
Sender consumes remote receiver fact
    ↓
PerReceiverTransportMode          (sender execution decision)
    MULTICAST | UNICAST_FALLBACK
```

`PerReceiverTransportMode` is **not** a receiver capability dimension. It is a
**sender execution decision** derived from consumed liveness facts plus sender policy.

### Frozen rules

```text
Liveness MUST be evaluated continuously during active Conference media transport.
Liveness MUST NOT be satisfied by a one-shot probe at session start.
Liveness loss MUST be detectable during an active session.
Receiver-published DEAD MUST cause sender downgrade without user leave/rejoin.
```

### Rationale (E3a.3b)

M02 demonstrated **establish-then-die** ingress: receive window from seq 0, then
permanent 0% with join still reported healthy. A session-start gate would classify this
device as multicast-capable and fail silently mid-session.

### Receiver-published liveness dimensions (contract surface)

Each receiving Module observes and MAY publish at minimum:

```text
MulticastReceiveLiveness   ALIVE | SUSPECT | DEAD
lastLivenessEvidenceMs     wall clock of last qualifying local receive evidence
```

Publication MUST carry sufficient **freshness / ordering identity** to reject stale
state at consumers. Whether that identity is expressed as epoch, revision,
generation+counter, or another scheme is **not** frozen at ADR layer — it belongs in
the capability publication / control-plane amendment (Profile 03 or successor).

Open questions intentionally deferred:

```text
process restart semantics
network-change scope
conference-generation interaction
cross-sender revision comparison
```

Exact thresholds and probe cadence are implementation parameters **outside** this
ADR, but MUST satisfy:

```text
DEAD (published) → sender MUST use UNICAST_FALLBACK for that receiver
SUSPECT → sender MUST NOT upgrade to MULTICAST without fresh ALIVE publication
ALIVE → sender MAY use MULTICAST only while transport scope + MulticastLock held
```

### Liveness ownership granularity (evidence track)

Whether liveness cache affinity is primarily `(device)` or
`(device × network environment)` is **not** decided here. E3a.3f-1 (L2 domain
replacement) classifies re-evaluation granularity only. It does **not** gate this ADR
and does **not** block the implementation main line.

---

## Decision 3 — Fallback transition contract

When a receiver publishes `MulticastReceiveLiveness = DEAD`, the sender MUST transition
that receiver's `PerReceiverTransportMode` to `UNICAST_FALLBACK` and continue delivery on
the correctness path.

### Frozen transition rules

```text
Downgrade is per-receiver, not global session tear-down.
Downgrade MUST NOT mutate Membership, conferenceEpoch, or mediaKeyEpoch.
Downgrade MUST NOT require Conference re-negotiation or owner action.
Sender begins (or continues) unicast replication to affected receiver(s).
Other receivers on multicast optimization path MAY remain on MULTICAST.
Upgrade to MULTICAST → only on fresh receiver-published ALIVE + explicit sender mode transition.
```

### Observability (minimum)

```text
Evidence MUST record:
  remoteModuleId
  prior PerReceiverTransportMode
  new PerReceiverTransportMode
  consumed MulticastReceiveLiveness at transition
  publication freshness / ordering identity at transition (scheme per control-plane amendment)
```

Downgrade is a **transport execution** event. It MUST NOT be projected as Membership
leave, recovery failure, or completion input.

### Forbidden

```text
Silent mid-session audio loss with no sender mode transition
Treating 0% multicast as "receiver left"
Using UI ONLINE / joinSucceeded as liveness proof
Sender inferring remote RX liveness without receiver-published fact
```

---

## Decision 4 — Fan-out capacity boundary (`N_max`)

V1 correctness uses **sender-side replication** only.

### Frozen V1 constraints

```text
Replicator role     = current sender Module (no relay, no elected replicator)
Topology authority  = unchanged; NO new owner/topology protocol for replication
N_max               = explicit frozen constant; MUST be set before cutover capacity sign-off
```

`N_max` bounds how many simultaneous unicast fan-out targets one sender may serve. It
exists because sender uplink and CPU replication cost grow linearly with group size.

**Envelope closure:** If the declared V1 Conference member envelope exceeds `N_max`, the
correctness contract does not hold. Example:

```text
8-member Conference (sender + 7 others) → required worst-case fan-out = 7 → N_max >= 7
10-member Conference                    → required worst-case fan-out = 9 → N_max >= 9
```

Admission / product envelope and `N_max` MUST be co-designed. See G-RES-1 / G-RES-3.

### Capacity evidence (not decided here)

ADR-0059 previously framed 8–10 member envelope as **multicast-underlay** risk. Under sender-side unicast fan-out, `N_max` MUST be re-evidenced against:

```text
sender uplink throughput at nominal Conference packet rate
sender CPU / pacing headroom
worst-case simultaneous receivers on fallback
```

Until that evidence exists, Media Engine cutover remains **NOT AUTHORIZED** (see §Authorization gate).

### Explicit non-goals (V1)

```text
NO relay Module
NO anchor replicator election
NO second-hop forwarding tree in App layer
NO "save fan-out by inventing topology authority"
```

If future product needs exceed `N_max`, the response is a **new ADR** for relay/topology — not an implicit V1 extension.

---

## Decision 5 — Liveness publication is a contract surface (not sender mode)

Mixed-mode delivery requires receivers to publish liveness and senders to consume it.
**Publication carries observation; transport mode remains sender execution.**

### Frozen contract facts

```text
Each receiving Module MAY publish its local MulticastReceiveLiveness view.
Sender MUST consume per-receiver published liveness to choose PerReceiverTransportMode.
Liveness publication MUST be propagatable without Membership mutation.
Liveness publication is NOT a Profile 01 Membership Fact.
PerReceiverTransportMode is owned by the sender, not published as receiver capability.
```

This is a **new control-plane contract surface** adjacent to ADR-0058 media execution.
Wire format, channel, snapshot rules, and freshness/ordering identity scheme are
deferred to a capability publication / control-plane amendment.

**Boundary:** Profile 02 is media wire. It MUST NOT absorb receiver liveness negotiation
protocol. Whether publication lives in Profile 03 or a dedicated successor profile is
decided in the next amendment round — not here.

---

## Amendment to ADR-0059

ADR-0059 remains ACCEPTED as a statement of underlay **optimization** assumptions, not correctness guarantees.

### Before (ADR-0059 §Decision)

Underlay multicast reliability was treated as a **product dependency** allowing ADR-0058 to proceed without lab Gate 0.

### After (this ADR)

```text
ADR-0059 scope:
  optimization-path underlay multicast MAY still be assumed for mesh efficiency
  when capability evidence supports it

ADR-0059 MUST NOT be cited as:
  proof that Conference correctness requires multicast
  reason to block unicast fallback implementation
  substitute for continuous capability / failover contract
```

ADR-0059 capacity gates (8–10 member envelope) remain relevant as **risk tracking**, but MUST be reinterpreted under sender-side fan-out capacity evidence before cutover.

---

## Authorization gate (replaces "multicast underlay PASS")

Media Engine / Meeting integration cutover is **NOT AUTHORIZED** until all of:

```text
G-RES-1  Correctness fallback path implemented and evidenced:
           sender-side unicast fan-out proven for every admitted receiver
           within the declared V1 Conference envelope

G-RES-2  Continuous liveness + mid-session downgrade contract implemented:
           receiver publishes MulticastReceiveLiveness;
           sender owns PerReceiverTransportMode;
           no session-start-only qualification

G-RES-3  N_max=9 contract CLOSED (C1–C4); evidence 3×PASS@N9 PENDING;
           ProvenNMax >= 9 via 3×VALID PASS @ N=9 (C4 E1)

G-RES-4  Liveness publication / consumption contract specified
           (receiver observation → sender mode selection; freshness/ordering identity)

G-RES-5  E2b / Profile conformance updated for resilience semantics
           (R13 MulticastLock remains; add fallback acceptance criteria)
```

```text
Profile 03 / G-RES-4 / G-RES-3 status (2026-09-03):
  G-RES-2 contract semantics (R1–R10)     CLOSED
  G-RES-2 implementation/evidence           PENDING
  G-RES-4 semantic contract (P1–P7)         CLOSED
  G-RES-4 implementation/evidence           PENDING
  G-RES-3 RequiredNMax = 9                  FROZEN (C1 ACCEPT)
  G-RES-3 contract (C1–C4)                  CLOSED (E1 ACCEPT)
  G-RES-3 evidence (3×PASS @ N=9)             PENDING
  Next: implement harness; collect qualifying evidence
  outline: docs/analysis/0060-gres3-capacity-envelope-contract-outline.md
```

Multicast optimization qualification is **orthogonal**:

```text
multicast underlay GREEN  →  MAY expand optimization coverage
multicast underlay FAIL   →  MUST NOT block correctness path
```

E3a may continue as optimization-path evidence. It is no longer a correctness gate.

---

## Evidence tracks (repositioned)

| Track | Purpose | Gates architecture? |
|-------|---------|---------------------|
| **E3a.3f-0** | Product-level vendor multicast control audit (read-only) | **NO** — implementation feasibility for optimization coverage only |
| **E3a.3f-1** | L2 domain replacement (M03 hotspot → M02) | **NO** — capability ownership granularity only |
| **E3a vendor A/B** | Single-variable device interventions | **NO** — optimization workaround search only |

```text
3f-1 outcomes:
  recovery on alternate L2  →  capability is (device × network environment)
  still 0% on alternate L2  →  device affinity stronger

Neither outcome reopens multicast-only correctness.
```

---

## Consequences

```text
ADR-0058 multicast wire/runtime design remains for optimization path.
ADR-0056 deletion still blocked until G-RES-1..5 satisfied.
Profile 02/03 implementation MUST add unicast fan-out + capability contract
  before Media Engine cutover authorization.
E3a investigation may narrow to optimization qualification / vendor coverage.
Product positioning: devices like M02 become "no multicast optimization"
  not "Conference broken" — provided fallback path works.
```

### What this ADR explicitly does not decide

```text
Liveness publication wire schema / channel     → CLOSED (G-RES-4 P1–P7)
Exact N_max numeric value                      → CLOSED at 9 (G-RES-3-C1)
Freshness/ordering identity scheme             → CLOSED (Profile 03 R4)
Unicast SRTP fan-out keying details            → Profile 02 amendment scope
Receiver liveness + sender mode FSM            → Profile 03 amendment scope
Whether ADR-0056 Anchor path returns if ProvenNMax < 9
```

Follow-on amendment boundaries (next round):

```text
Profile 02 amendment ADJUDICATION COMPLETE (A1–A10)
  closeout: docs/analysis/0058-profile-02-a10-acceptance-corpus-closeout.md
  C1–C8 hex corpus PASS → Profile 02 resilience amendment CONTRACT COMPLETE
  Media Engine: NOT AUTHORIZED (G-RES-2/3/4/5 open)
  Next: G-RES-3 evidence collection (adjudication COMPLETE)
  outline: docs/analysis/0058-profile-03-resilience-amendment-outline.md

Profile 03 amendment ADJUDICATION COMPLETE (P03-R1..R10 ACCEPTED)
  implementation acceptance C1–C10 + X1–X3 PENDING
  closeout: docs/analysis/0058-profile-03-r10-acceptance-closeout-adjudication.md
  G-RES-2 contract semantics CLOSED; G-RES-2 evidence PENDING

G-RES-4 publication transport ADJUDICATION COMPLETE (P1–P7 ACCEPTED)
  implementation acceptance C1–C12 + X1–X4 PENDING
  closeout: docs/analysis/0060-gres4-p7-acceptance-closeout-adjudication.md
  G-RES-4 semantic contract CLOSED; G-RES-4 evidence PENDING
  outline: docs/analysis/0060-gres4-publication-transport-contract-outline.md

G-RES-3 capacity envelope ADJUDICATION COMPLETE (C1–C4 ACCEPTED)
  RequiredNMax=9; B1 benchmark; T1 gates; E1 3-run closeout
  evidence: 3×VALID PASS @ N=9 PENDING
  closeout: docs/analysis/0060-gres3-c4-evidence-closeout-adjudication.md
```

Those require follow-on implementation profiles / capacity runs under this contract.

---

## G-RES-3 capacity envelope (adjudication complete)

```text
Contract: N_max=9; B1 workload; T1 gates; E1 3×PASS evidence rule
Status: adjudication CLOSED; evidence PENDING
Next: benchmark harness + 3×N=9 qualifying runs
```

---

## References

- `docs/analysis/adr0058-e3a-underlay-multicast-status.md`
- `docs/analysis/adr0058-e3a3f0-mtk-multicast-filter-product-control-audit.md`
- `docs/analysis/adr0058-e3a3f1-l2-domain-replacement-run-card.md`
- `docs/analysis/adr0058-e3a3e-m02-multicast-rx-ab-run-card.md`
- `docs/analysis/0058-profile-03-resilience-amendment-outline.md`
- `docs/analysis/0060-gres3-capacity-envelope-contract-outline.md`
- `docs/analysis/0060-gres3-c4-evidence-closeout-adjudication.md`
- `docs/analysis/0060-gres3-c3-pass-metrics-adjudication.md`
- `docs/analysis/0060-gres3-c2-sender-capacity-test-contract-adjudication.md`
- `docs/analysis/0060-gres3-c1-product-capacity-envelope-adjudication.md`
- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p7-acceptance-closeout-adjudication.md`
- `docs/analysis/0060-gres4-p6-fence-lineage-integration-adjudication.md`
- `docs/analysis/0060-gres4-p5-loss-reorder-gap-adjudication.md`
- `docs/analysis/0060-gres4-p4-snapshot-bootstrap-adjudication.md`
- `docs/analysis/0060-gres4-p3-cadence-freshness-adjudication.md`
- `docs/analysis/0060-gres4-p2-transport-channel-adjudication.md`
- `docs/analysis/0060-gres4-p1-publication-topology-adjudication.md`
- `docs/analysis/0058-profile-03-r10-acceptance-closeout-adjudication.md`
- `docs/analysis/0058-profile-03-r9-restart-generation-fences-adjudication.md`
- `docs/analysis/0058-profile-03-r8-transition-overlap-adjudication.md`
- `docs/analysis/0058-profile-03-r7-upgrade-execution-adjudication.md`
- `docs/analysis/0058-profile-03-r6-downgrade-execution-adjudication.md`
- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r3-liveness-timers-hysteresis-adjudication.md`
- `docs/analysis/0058-profile-03-r2-liveness-state-semantics-adjudication.md`
- `docs/analysis/0058-profile-03-r1-liveness-observation-ownership-adjudication.md`
- `docs/analysis/0058-profile-02-amendment-unicast-fanout-outline.md`
- `docs/analysis/0058-profile-02-a8-send-failure-isolation-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
- `docs/analysis/0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md`
- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
- `docs/analysis/0058-profile-02-amendment-unicast-fanout-outline.md`
- `android-board-talkback/.../UnderlayMulticastProbeRunner.kt` (MulticastLock held)
- `android-board-talkback/.../AndroidWifiMulticastLockSeam.kt` (product seam)
