# Conference MESH LIVE Presentation Contract Review

## Status

**CLOSED** (2026-08-26, signed)

**Implementation authorization:** **NONE**

**Amendment:** **NONE**

**Track:** B — **CLOSED** (independent of Primary Native SRD Fault / Track A)

**Parent contracts:**

- [ADR-0056](../adr/0056-conference-topology-first.md) Decision 5 · Conference health chain
- [0056-phase-2-5-health-projection-contract.md](./0056-phase-2-5-health-projection-contract.md) §3 ANCHOR vs MESH
- [0056-phase-3-health-ui-contract.md](./0056-phase-3-health-ui-contract.md) U4

**Field seam evidence (non-authoritative for PNSRD):**

- Session `6369adc1-466d-403e-ab33-f75344ed11ff` (M01 host, 2026-08-26 ~17:17)
- `CONFERENCE_RUNTIME_PROJECTION phase=ACTIVE connected=2` while `CONFERENCE_HEALTH l4RoomState=NOT_ESTABLISHED`
- UI remained CONNECTING despite ICE CONNECTED + SRD PASS on M02/M03

---

## Signed verdict (2026-08-26)

```text
Q1 = NO
  3-person MESH is runtime-valid bootstrap/field topology,
  but not a product-valid LIVE Conference under ADR-0056.

Q2 = N/A
Q3 = N/A

Track B = CLOSED
No amendment
No implementation
```

---

## Frozen preamble (do not re-litigate in implementation)

```text
Runtime ACTIVE ≠ Room ONLINE

Health model currently has no valid MESH ONLINE semantics

Resolver is not authorized to bypass Health and inspect ICE directly
```

Presentation follows Health when `healthUi` is present:

```text
ConferenceHealthBinder → ConferenceHealth → ConferenceHealthUiProjection
        ↓
ConferenceDisplayStateResolver (roomOnline → LIVE)
```

This is **contract-faithful behavior**, not a Resolver defect.

---

## Problem statement (COGNITIVE / OPERATIONAL GAP — not contract bug)

```text
ADR-0056 / Phase 2-5 / U4
        ↓
MESH topology allowed at runtime (3-device field bootstrap)
        ↓
Health model: MESH → ActualMediaEdgeSet = ∅ → mediaUsable = false
        ↓
Phase 3 UI: roomOnline ← health.mediaUsable / l4RoomState
        ↓
ConferenceDisplayStateResolver → LIVE = false → CONNECTING
        ↓
CONTRACT-FAITHFUL (not Resolver defect; not amendment trigger)
```

**Signed conclusion:** No contract bug. The observed seam is **runtime-valid MESH** vs **product LIVE semantics** — a cognitive/operational gap, not a Health implementation error.

```text
3p MESH:
  runtime ACTIVE / media CONNECTED
  ≠ product LIVE pill

4p+ Anchor/SFU:
  enters formal Conference LIVE / Health model
```

**Isolation from Track A:**

```text
MESH LIVE contract review
        ⊥
Primary Native SRD Fault / mutex closure / R3 hang capture
```

Do not update native RCA, R3 verdict, or mutex closure status based on this seam.

---

## Q1 — Is 3-person MESH Conference product-valid LIVE?

**Question:**

> Does the product treat a 3-participant MESH Conference as a normal, user-facing LIVE meeting?

**Distinction (frozen for this review):**

```text
runtime-valid topology     ≠     product-valid LIVE state
```

A Conference may reach `CONFERENCE_RUNTIME_PROJECTION phase=ACTIVE` and ICE CONNECTED without satisfying any **room ONLINE** contract today.

**Current contract posture:**

| Source | MESH LIVE semantics |
|--------|---------------------|
| ADR-0056 | Conference target = 4+ Anchor/SFU-lite; 3-device runs MESH as field bootstrap |
| Phase 2-5 §3 | MESH: `ActualMediaEdgeSet = ∅`; no ANCHOR star health |
| Phase 3 U4 | MESH → room **NOT_ONLINE** (fixture enforced) |

**If Q1 = YES:** Health contract is **incomplete** for the product topology users actually run.

**If Q1 = NO:** UI CONNECTING for MESH is **expected**; field and PNSRD must not use LIVE pill as success signal.

### Q1 decision

| Option | Meaning |
|--------|---------|
| **YES** | 3p MESH Conference is product LIVE; proceed to Q2 |
| **NO** | MESH is bootstrap/dev/field only; document NOT_ONLINE as correct; close Track B at contract level |
| **DEFER** | Product decision pending; Track B stays REVIEW; no implementation |

**Decision:** **NO** (signed 2026-08-26)

**Rationale:** 3-person MESH is **runtime-valid bootstrap/field topology**, not a **product-valid LIVE Conference** under ADR-0056. Field evidence (`6369adc1`) confirms MESH → SESSION_CREATED → phase=ACTIVE → SRD PASS → ICE CONNECTED → media usable — but **runtime-valid ≠ product-valid LIVE**. Frozen contracts already state: ADR-0056 Conference target = 4+ Anchor/SFU-lite; Phase 2-5 MESH does not enter ANCHOR health model; Phase 3 U4 MESH = room NOT_ONLINE. `ConferenceDisplayStateResolver` yielding LIVE=false is **faithful execution** of the existing product health contract. UI CONNECTING while media is connected is therefore **expected**, not a defect.

---

## Q2 — If Q1 = YES, who is the LIVE authority?

**Precondition:** Q1 = YES. If Q1 = NO or DEFER, Q2/Q3 are **N/A**.

**Forbidden path (architecture violation):**

```text
ConferenceDisplayStateResolver
        → read ICE / runtime projection directly
        → LIVE
```

UI must not re-invent `MEDIA_USABLE`.

### Options

| ID | Path | Summary |
|----|------|---------|
| **A** | Extend L4 / Health | Define MESH `ActualMediaEdgeSet` + `MediaUsable` in existing Health → `roomOnline` chain |
| **B** | Bootstrap presentation contract | Explicit amendment for MESH bootstrap LIVE semantics without full L4 parity |
| **C** | Keep NOT_ONLINE | Accept CONNECTING UI; operational docs mark UI non-authoritative (see Related Operational Note) |

**Review lean (not ACCEPT):** Option **A** preserves the frozen downward fact flow:

```text
Topology → Media observation → Health → UI
```

Option B is viable only if it does not become a permanent Resolver bypass. Option C is valid if Q1 = NO.

### Q2 decision

| Option | Status |
|--------|--------|
| A | `________` |
| B | `________` |
| C | `________` |

**Decision:** **N/A** (Q1 = NO)

**Rationale:** Q2 applies only when 3p MESH is declared product LIVE. Option B (bootstrap presentation shim → LIVE) would bypass ADR-0056 product topology positioning and is **explicitly rejected** as a workaround path.

---

## Q3 — If Q2 = A (extend L4), what is MESH MediaUsable?

**Precondition:** Q1 = YES **and** Q2 = A.

**Goal:** Define product-explainable MESH room ONLINE using the same vocabulary as ANCHOR Health, not ICE shortcuts.

### Must define (all three)

```text
MESH
  → ActualMediaEdgeSet        = ?
  → MediaUsable               = ?
  → roomOnline (L4 / UI)      = ?
```

### Forbidden shortcuts

```text
ICE_CONNECTED > 0                    → roomOnline          // forbidden
runtime phase ACTIVE alone           → roomOnline          // forbidden
host isConferenceUiReady == true     → roomOnline          // forbidden
Resolver reads ICE                   → LIVE                // forbidden
```

### Review criteria (minimum bar for a contract amendment)

1. **ActualMediaEdgeSet** for MESH must be **topology-authoritative** (admitted edges in `ConferenceTopologySnapshot`), not a runtime ICE counter.
2. **MediaUsable** must be derived from **media observations on admitted edges** (same pattern as ANCHOR H1/H2), consistent with INV-056-14:
   ```text
   obligationOpen / recovery in-flight alone MUST NOT block ONLINE
   when media is actually usable on an admitted edge
   ```
3. **Minimum usable media set** must be stated in product terms, e.g.:
   ```text
   required admitted edges for this conference topology
   all required edges have usable observation
   sessionEstablished == true
   ```
   Exact roster/edge rule is a **product decision** inside this review — not `connected > 0`.
4. **MESH vs ANCHOR** must remain distinguishable (`anchorHealthModel = false` for MESH).
5. Phase 2-5 H7 and Phase 3 U4 fixtures must be **superseded or amended** explicitly — not silently changed in code.

### Q3 decision (contract text only — no implementation)

**Decision:** **N/A** (Q1 = NO; no MESH room-health contract required for bootstrap topology)

| Field | Decision |
|-------|----------|
| MESH `ActualMediaEdgeSet` | N/A |
| MESH `MediaUsable` predicate | N/A |
| MESH `roomOnline` mapping | N/A |
| Minimum usable media set (product) | N/A |

---

## Related operational note — Track A (PNSRD R3)

**MESH UI CONNECTING must not drive field behavior** on PNSRD runs.

```text
UI CONNECTING / LIVE     = non-authoritative

Authoritative adjudication:
  SRD_ENTER / SRD_EXIT / SRD_NATIVE_CALL_ENTER / SRD_NATIVE_CALL_EXIT
  ICE_CONNECTED / CONFERENCE_JOIN_LATENCY markers
  CONFERENCE_RUNTIME_PROJECTION (phase, connected, awaiting)
  SRD_WATCHDOG_GAP / WATCHDOG
  lease / mutex facts (instrumentation)
```

**Operator rules (R3 run card addendum):**

```text
Do not repeat-create meetings because UI shows CONNECTING
Do not early-hangup because UI has not shown LIVE
Do not treat UI LIVE/CONNECTING as R3 PASS/FAIL
If runtime projection shows ACTIVE + ICE connected, meeting substrate may be valid
  even when UI pill remains CONNECTING

For 3p MESH bootstrap conferences, CONNECTING UI is contract-consistent;
runtime ACTIVE / ICE CONNECTED may still indicate a functioning substrate
```

This note does **not** change R3 verdict criteria (hang capture only).

---

## Exit criteria

| Gate | Requirement | Status |
|------|-------------|--------|
| Review complete | Q1 signed | **DONE** (Q1 = NO) |
| Q2 required | Only if Q1 = YES | **N/A** |
| Q3 required | Only if Q2 = A | **N/A** |
| Amendment | Only if Q1 = YES + path chosen | **NONE** |
| Implementation | Requires amendment | **NOT AUTHORIZED** |
| Track B | Close at contract level | **CLOSED** |
| Track A | Independent | **Unblocked** — continue R3 per run card |

---

## Explicit non-scope

```text
ConferenceL4MediaUsableContract code changes
ConferenceDisplayStateResolver code changes
Primary Native SRD RCA / mutex fix / R3 on-hang procedure changes
WiFi recovery / RNA / completion predicate / membership
Treating this seam as SRD native fault resolution
```

---

## Current dual-track status

```text
Track A — Primary Native SRD Fault
  RCA                  PASS
  native stack         PASS
  S2-b thread graph    PASS
  mutex object         OPEN
  R3                   NOT CAPTURED
  native fix           BLOCKED

Track B — Conference MESH LIVE Presentation Contract Review
  observed seam        DOCUMENTED (operational gap, not contract bug)
  Q1                   NO (signed)
  Q2 / Q3              N/A
  amendment            NONE
  implementation       NOT AUTHORIZED
  track status         CLOSED
```
