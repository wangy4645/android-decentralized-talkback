# CPP Per-Edge Media Fact Contract

## Status

**CONTRACT PASS** (2026-08-18) · P1–P8 **8/8 PASS** · **Implementation UNIT WIRED** · **4p flap retest NOT AUTHORIZED**

**Track:** independent CPP follow-up. **Not** an ADR-0056 topology / capacity / Health / Recovery phase.

**Field that opened this track:** `talkback/logs/4p-obs-spoke-flap-20260818-170847/`  
M04 spoke WiFi flap: M01 showed M04 drop; M03 stayed `connected=4` / no RECONNECTING.

**Prerequisite:** 4p VIA_ANCHOR from ActualMediaEdgeSet (Patch 1) is necessary but **insufficient**. It used local ICE-to-anchor as a proxy for the remote star edge.

**Gate:**

```text
CPP Per-Edge Media Fact Contract   PASS
P1–P8                             8/8 PASS
Implementation                    UNIT WIRED (FactsAdapter + HELLO digest)
4p flap retest                    NOT AUTHORIZED
Gate 3 / 8p                       不受影响
ADR-0056 topology/recovery        FROZEN
```

Fixtures: `ConferencePerEdgeMediaFactPFixturesTest`  
**Next:** static inspection, then same 4p M04 WiFi flap. **Not** Gate 3. **Not** 8p.

---

## 1. Frozen split

```text
ActualMediaEdgeSet
    = topology admission fact

PerEdgeMediaUsability
    = runtime media fact on an admitted MediaEdge

CPP VIA_ANCHOR
    = that star edge is admitted
      AND that edge's usability fact is present, valid, and usable=true
```

Forbidden proxy:

```text
VIA_ANCHOR
    = local ICE to Anchor
```

Room vs avatar (orthogonal):

```text
ConferenceHealth.mediaUsable = true  →  Room LIVE
avatar RECONNECTING                  →  per-participant, allowed while LIVE
```

---

## 2. Artifact

```text
ConferencePerEdgeMediaFactContract
```

Fact identity **must** be `MediaEdge(anchorModuleId, remoteModuleId)` and **must** be `∈ ActualMediaEdgeSet`.  
A fact whose edge is not admitted is ignored. It MUST NOT admit an edge.

---

## 3. Producer / consumer

### Producer (write)

Only an **endpoint** of that `MediaEdge` may produce its usability fact.

```text
M01→M04
    ↓
M01 and/or M04 produce fact for MediaEdge(M01, M04)
```

Non-endpoints MUST NOT produce a fact for that edge.

### Consumer (read)

CPP / `ConferencePresenceFactsAdapter` (and only such presence projection) **reads**.

```text
M03
  ↓
read MediaEdge(M01, M04) fact
  ↓
VIA_ANCHOR | RECONNECTING | (not confirmed usable)
```

Non-endpoints MUST NOT infer usability from roster, from local ICE to a third module, or from peer-pair absence.

---

## 4. Fail-closed

```text
edge admitted
+ media fact missing
        →  MUST NOT display confirmed usable
        →  MUST NOT emit VIA_ANCHOR as NORMAL / mediaConnected
```

Same treatment (not confirmed usable):

```text
fact stale
fact conferenceId mismatch
fact.anchorEpoch != snapshot.anchorEpoch
fact.meshGeneration < snapshot.meshGeneration
```

`joined` membership is unchanged. Do **not** name this OFFLINE.

When:

```text
membership JOINED
+ edge admitted
+ not confirmed usable
```

avatar / recovering axis:

```text
RECONNECTING
```

not first-join JOINING as the preferred label when the edge is already admitted.

---

## 5. Lineage (stale / generation / epoch)

Align with existing CPP snapshot gating. Do not invent a third clock.

| Field | Rule |
| --- | --- |
| `conferenceId` | must equal current snapshot |
| `anchorEpoch` | must **equal** current `ConferenceTopologySnapshot.anchorEpoch` (reject older; same as CPP compose) |
| `meshGeneration` | reject if `fact.meshGeneration < snapshot.meshGeneration`; do not require equality-only if fact is newer |
| `producedAtMs` | if `now - producedAtMs > CPP stale window` (same `DEFAULT_STALE_AFTER_MS`) → treat as missing |
| conflict | multiple valid facts for one `MediaEdge`: take latest `producedAtMs`; `usable=true` only if that latest fact says so |

Missing / rejected fact = fail-closed (section 4).

---

## 6. ANCHOR mapping (normative)

Observer `local`, remote member `R`, anchor `A`, snapshot ANCHOR.

**Incident star edge** (`local` is endpoint of `MediaEdge(A,R)`):

```text
fact usable=true   →  R = DIRECT (FRESH)     avatar NORMAL
fact usable=false
 or missing/stale  →  R not confirmed usable  avatar RECONNECTING
```

**Non-incident** (`local` is another spoke):

```text
MediaEdge(A,R) admitted
+ fact usable=true   →  R = VIA_ANCHOR (FRESH)  avatar NORMAL
+ fact usable=false
  or missing/stale   →  R not confirmed usable   avatar RECONNECTING
```

Local ICE to `A` MAY be used only for the **incident** edge `MediaEdge(A, local)`.  
It MUST NOT stand in for `MediaEdge(A,R)` when `R ≠ local`.

---

## 7. MESH

```text
MESH ⇒ ActualMediaEdgeSet = ∅
     ⇒ this contract produces no star facts
     ⇒ MUST NOT emit VIA_ANCHOR from PerEdgeMediaUsability
```

MESH presence remains ICE-adjacency (existing path). This contract does not rewrite MESH.

---

## 8. Explicit non-scope (frozen)

```text
MUST NOT mutate ActualMediaEdgeSet
MUST NOT add/remove MediaEdge on WiFi flap
MUST NOT emit extra AdmittedRecoveryTarget
MUST NOT create M02→M04 / M03→M04
MUST NOT change ConferenceHealth.mediaUsable rule (Decision 5)
MUST NOT change room LIVE = mediaUsable
MUST NOT reopen ADR-0056 topology / capacity / Phase 3 Health
MUST NOT run Directed #5 / Gate 3 for this contract
```

After flap, legal topology/recovery remain:

```text
ActualMediaEdgeSet = {M01→M02, M01→M03, M01→M04}
RecoveryTarget     = star only (M01→M04 for the down spoke)
```

---

## 9. Fixtures P1–P8

Shared: 4p ANCHOR `members = M01,M02,M03,M04`, `anchorId = M01`,  
`actualMediaEdges = {M01→M02, M01→M03, M01→M04}`.  
Do **not** reuse K/U/R/C/AT/T numbers.

### P1 — endpoint usable → DIRECT

M01 observes `MediaEdge(M01,M04)` `usable=true`.  
M01 projection: M04 `DIRECT`, `mediaConnected`, avatar NORMAL.  
Edges unchanged.

### P2 — endpoint unusable → RECONNECTING, admission kept

M01 observes `MediaEdge(M01,M04)` `usable=false`.  
M01: M04 not confirmed usable; avatar RECONNECTING.  
`M01→M04 ∈ actualMediaEdges`. No peer-pair. RecoveryTarget still star.

### P3 — non-endpoint consumes usable → VIA_ANCHOR

M03 **reads** `MediaEdge(M01,M04)` `usable=true`.  
M03 local ICE to M01 MAY be up.  
M03: M04 `VIA_ANCHOR` NORMAL.  
M03 MUST NOT produce a `M03→M04` fact.

### P4 — non-endpoint consumes unusable → RECONNECTING

M03 reads `MediaEdge(M01,M04)` `usable=false`.  
M03 ICE to M01 still connected.  
M03: M04 RECONNECTING, **not** VIA_ANCHOR-as-NORMAL.  
`actualMediaEdges` still 3. No `M03→M04` recovery edge.

### P5 — missing fact fail-closed

M03 has no valid fact for `MediaEdge(M01,M04)`.  
M03 ICE to M01 connected.  
MUST NOT show M04 confirmed VIA_ANCHOR.  
JOINED + admitted → RECONNECTING.

### P6 — stale / epoch / generation fail-closed

Fact with wrong `conferenceId`, or `anchorEpoch != current`, or `meshGeneration < current`, or stale `producedAtMs`.  
Treat as P5. MUST NOT keep a previous VIA_ANCHOR NORMAL from the rejected fact.

### P7 — MESH produces no star VIA_ANCHOR fact

MESH snapshot, empty `actualMediaEdges`.  
No `MediaEdge(M01,M04)` fact.  
Projection MUST NOT emit VIA_ANCHOR from this contract.

### P8 — read-only vs Health / Recovery / Topology

Project P4 input. After project:

- `actualMediaEdges` identical
- no extra recovery targets
- room `mediaUsable` still follows Health (any other admitted edge may keep room LIVE)
- avatar M04 RECONNECTING **and** room LIVE MAY both be true

---

## 10. Field retest (after impl only)

Same 4p flap: disconnect **M04** WiFi only. Do not flap M01. Not Gate 3.

Expect while M04 media down:

```text
M01: M04 RECONNECTING
M02: M04 RECONNECTING
M03: M04 RECONNECTING
ActualMediaEdgeSet still 3
RecoveryTarget still M01→M04
Room LIVE if remaining admitted edges usable
```

After restore:

```text
M01: M04 DIRECT
M02: M04 VIA_ANCHOR
M03: M04 VIA_ANCHOR
```
