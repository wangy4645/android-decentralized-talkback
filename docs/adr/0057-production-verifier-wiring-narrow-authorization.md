# ADR-0057: Production Verifier Wiring Narrow Implementation Authorization

## Status

**IMPLEMENTED — CLOSED** (2026-08-31)

**Conformance:** PW-EG1..PW-EG6 PASS · ADR-0057 regression PASS

```text
ADR-0057 — Production Verifier Wiring

Implementation Conformance:
  PASS

Status:
  IMPLEMENTED
  CLOSED

PW-IA-T1 Trust Readiness / Re-evaluation:
  CLOSED / PASS

PW-IA-T2 Unique Production Verifier Selection:
  CLOSED / PASS

PW-IA-T3 Single Promotion Seam:
  CLOSED / PASS

PW-EG1..PW-EG6:
  PASS

Production Trust Integration
Implementation Conformance:
  PASS

Field Establishment Ceremony / Transport:
  NOT IMPLEMENTED / NOT CLAIMED

Track C:
  OPEN

ADR-0057 COMPLETE:
  NO
```

**Parents (all CLOSED — do not reopen):**

- [PAP Contract](./0057-profile-authentication-establishment-provisioning-contract.md)
- [PTI Layer A](./0057-pti-layer-b-production-profile-authentication-narrow-authorization.md) — via Layer B auth doc chain
- [PTI Layer B](./0057-pti-layer-b-production-profile-authentication-narrow-authorization.md)
- [Operational Establishment](./0057-operational-establishment-narrow-authorization.md)
- GBC Core / Delivery / Wire — **PASS / FROZEN**
- PV-1 / PV-2 — **CLOSED / PASS**

**Does NOT authorize:**

- ADR-0057 COMPLETE
- Track C field validation
- Field Establishment Ceremony / Transport
- Operator provisioning UI / USB / QR / MDM / LAN transport
- Anchor replacement / root rotation / certificate hierarchy
- `talkback-app` `TaskProfile` migration as trust root
- Changes to PV-1 verifier semantics, PV-2 issuance, PTI acceptance semantics, or GBC convergence rules
- Injectable / fixture verifier as production runtime default

---

## Why this knife exists

Individual components are **IMPLEMENTED / PASS**:

```text
Device-side operational anchor establishment + persistence   PASS
ProductionProfileRevisionAuthenticator                       PASS
ProfileRevisionAcceptor                                      PASS
ProfileBackedGenerationFactTrustLookup                         PASS
ProductionGenerationFactVerifier                             PASS
VerificationBoundary                                         PASS
GBC                                                          PASS
```

The remaining gap is **production composition**:

```text
TalkbackCoordinator today:
  InjectableGenerationFactVerifier
  → VerificationBoundary
  → FactDeliveryOrchestrator
  → GBC
```

This knife authorizes replacing the production path with real components **without**
reopening crypto/trust architecture.

```text
OperationalTrustAnchorSource
        ↓
ProductionProfileRevisionAuthenticator
        ↓
ProfileRevisionAcceptor
        ↓
AcceptedLocalTrustState
        ↓
ProfileBackedGenerationFactTrustLookup
        ↓
ProductionGenerationFactVerifier
        ↓
VerificationBoundary
        ↓
GBC
```

Focus: **composition correctness**, not new cryptography.

---

## Authorized wiring chain (only)

```text
OperationalTrustAnchorSource          (OE product load seam)
        ↓
ProductionProfileRevisionAuthenticator
        ↓
ProfileRevisionAcceptor               (Layer A — unchanged semantics)
        ↓
AcceptedLocalTrustStateStore          (Layer A persistence)
        ↓
ProfileBackedGenerationFactTrustLookup
        ↓
ProductionGenerationFactVerifier      (PV-1 — unchanged semantics)
        ↓
VerificationBoundary                  (unchanged promotion rules)
        ↓
FactDeliveryOrchestrator              (unchanged orchestration)
        ↓
GroupBootstrapConvergenceWiring / GBC (unchanged convergence rules)
```

Profile revision authentication and trust-state acceptance **MAY** be invoked as
part of wiring bootstrap (e.g. load signed Profile delivery into acceptor), but
**MUST NOT** be bypassed or short-circuited by Coordinator authority.

---

## Implementation Authorization Tightenings (PW-IA-T1..T3)

### PW-IA-T1 — Trust Readiness / Re-evaluation

```text
UNVERIFIABLE due to trust-not-ready != permanent candidate invalidity

trust becomes ready
  → subsequently delivered / reacquired candidate MAY verify

Verifier: current evidence → outcome
GBC/acquisition: whether evidence still needs to be obtained

NO candidate replay cache
NO trust-readiness state machine
```

### PW-IA-T2 — Unique Production Verifier Selection

```text
ProductionCompositionRoot → ProfileBackedGenerationFactTrustLookup
                          + ProductionGenerationFactVerifier
                          + VerificationBoundary

TestCompositionRoot → may inject Fixture/Injectable verifier

Production construction failure → UNVERIFIABLE / fail closed
NOT → InjectableGenerationFactVerifier
```

### PW-IA-T3 — Single Promotion Seam

```text
ProductionGenerationFactVerifier SUCCESS
  → VerificationBoundary promotion only
  → FactDeliveryOrchestrator → GBC

Coordinator MUST NOT direct-write FactStore / invoke F1 from crypto SUCCESS
```

---

## Conformance gates (PW-1..PW-6)

### PW-1 — Real Adapter Selection

**Requirement:** Production runtime path **MUST** use:

```text
ProfileBackedGenerationFactTrustLookup
+ ProductionGenerationFactVerifier
```

bound to **Accepted Local Trust State** populated through the production
authentication + acceptance chain.

**Forbidden in production path:**

```text
InjectableGenerationFactVerifier as default runtime verifier
FixtureGenerationFactTrustLookup as production trust source
sharedSecret as Fact trust substitute
direct Coordinator mutation of accepted trust state
```

Harness / unit tests **MAY** continue using injectable verifiers in non-production
test classes.

### PW-2 — No Injectable Bypass

**Requirement:** Production build/runtime **MUST NOT** silently fall back to
`InjectableGenerationFactVerifier` or other test doubles when:

- operational anchor is absent
- trust state is empty
- Profile authentication fails
- lookup returns non-Found

Fallback to injectable verifier for “startup convenience” is **FORBIDDEN**.

Observable production behavior on missing trust:

```text
VerificationOutcome.Unverifiable
and/or Profile authentication rejection
→ no authority advancement
```

### PW-3 — Startup Ordering (priority gate)

**Requirement:** Trust unavailable at candidate arrival **MUST** fail closed.

```text
trust unavailable
  → UNVERIFIABLE / no authority advancement
  → does NOT clear existing good Current
  → does NOT drive F1 / fence / obligation

trust becomes available later
  → subsequent / retried candidate MAY verify
```

**Forbidden:**

```text
app starts
→ Coordinator starts first
→ trust state still loading
→ temporary fallback verifier
→ candidate enters wrong path
```

If trust bootstrap is asynchronous, production wiring **MUST** gate verification
on trust-readiness (anchor loaded + accepted trust state available for lookup) or
return `Unverifiable` until ready — **never** a permissive stub.

### PW-4 — Non-Regression

**Requirement:** Verifier / authentication / trust lookup failure **MUST NOT**:

- erase an existing accepted good Current generation
- directly emit F1 / fence / obligation convergence effects
- bypass `VerificationBoundary` promotion rules
- treat `VerifyFail` / `Unverifiable` as success

Existing ADR-0057 delivery harness outcomes (V1–V3) **MUST** remain PASS with
production verifier wired in harness-only composition tests.

### PW-5 — Authority Direction

**Requirement:** Coordinator **MAY**:

- deliver observations (candidates, wire payloads)
- execute GBC-returned effects

Coordinator **MUST NOT**:

- write operational anchor
- mutate Accepted Local Trust State outside `ProfileRevisionAcceptor`
- invent trust binding / key state from runtime evidence
- use election / Primary / HELLO / media / UI as trust authority
- promote candidates without `VerificationBoundary` outcome

```text
Coordinator = observation + effect executor
GBC = generation truth owner (unchanged)
VerificationBoundary = sole promotion point (unchanged)
```

### PW-6 — Production Composition Evidence

**Requirement:** Harness **MUST** exercise end-to-end production components:

```text
persisted operational anchor (OE product seam)
        ↓
authenticated signed Profile v2 delivery
        ↓
ProfileRevisionAcceptor (production auth)
        ↓
AcceptedLocalTrustState
        ↓
ProfileBackedGenerationFactTrustLookup
        ↓
ProductionGenerationFactVerifier
        ↓
VerificationBoundary.admit(candidate)
        ↓
FactDeliveryOrchestrator → GBC (harness)
```

**MUST NOT** use `InjectableGenerationFactVerifier` or fixture trust lookup in
PW-6 evidence path.

PW-6 proves **Production Trust Integration Implementation Conformance** at
library/runtime composition level — not field Track C.

---

## Evidence gates (PW-EG1..PW-EG6)

| Gate | Maps to | Minimum PASS evidence |
|------|---------|----------------------|
| PW-EG1 | PW-1 | Production factory/wiring selects `ProfileBackedGenerationFactTrustLookup` + `ProductionGenerationFactVerifier` |
| PW-EG2 | PW-2 | Absent trust / empty lookup → `Unverifiable`; no injectable fallback in production wiring |
| PW-EG3 | PW-3 | Trust-not-ready before bootstrap → `Unverifiable`; after bootstrap → verify may succeed; no Current erase |
| PW-EG4 | PW-4 | `VerifyFail` on bad candidate preserves accepted Current; V1–V3 harness PASS |
| PW-EG5 | PW-5 | Coordinator wiring audit: no anchor/trust write reach-through |
| PW-EG6 | PW-6 | Full production stack test: OE anchor → auth Profile → PV-1 verify → GBC accept |

Regression: OE (OE-EG1..6), Layer B (B-EG1..5), Layer A (PTI-G*), PV-1/PV-2,
ADR-0057 suite **MUST** remain PASS.

---

## Implementation boundaries

### Authorized (if AUTHORIZED)

```text
Production GBC trust wiring module / factory
  (compose anchor source, auth, acceptor, store, lookup, verifier, boundary)

TalkbackCoordinator production verifier injection
  (replace InjectableGenerationFactVerifier default in production path)

Trust-readiness gate for VerificationBoundary admission

PW-EG1..PW-EG6 harness (+ optional Coordinator integration smoke)

Bootstrap hook ordering:
  load OE anchor → (optional Profile accept) → expose lookup → enable verification
```

### Must not change

```text
ProductionGenerationFactVerifier verify semantics
ProfileRevisionAcceptor / Layer A rules
ProductionProfileRevisionAuthenticator semantics
OE establishment create-once rules
PV-2 issuance
GBC convergence predicate / obligation rules
InjectableGenerationFactVerifier (keep for harness tests)
Wire codec / delivery orchestrator promotion contract
```

### Suggested production factory shape (non-normative)

```text
GbcProductionTrustRuntime(
  anchorPersistence: OperationalTrustAnchorPersistence,
  acceptedTrustPersistence: AcceptedTrustStatePersistence?,
): GbcTrustWiringBundle

data class GbcTrustWiringBundle(
  val profileAcceptor: ProfileRevisionAcceptor,
  val verificationBoundary: VerificationBoundary,
  val trustReadiness: TrustReadinessGate,
)
```

Exact class names deferred; properties are normative.

---

## Claim boundary

| After PW PASS | May claim |
|---------------|-----------|
| PW-EG1..6 PASS | **YES** |
| Production Trust Integration Implementation Conformance | **YES** |
| Device-Side Operational Anchor Establishment / Persistence | **YES** (already PASS) |
| Field Establishment Ceremony / Transport | **NO** |
| Track C field validation | **NO** |
| ADR-0057 COMPLETE | **NO** |

**Maximum claim after PW implementation conformance PASS:**

```text
Production Trust Integration
Implementation Conformance:
  PASS

Production Verifier Wiring:
  IMPLEMENTED / PASS

Field Establishment Ceremony / Transport:
  NOT CLAIMED

Track C:
  OPEN

ADR-0057 COMPLETE:
  NO
```

Do **not** conflate Production Trust Integration PASS with ADR-0057 COMPLETE.
Track C remains the field evidence gate for the original convergence problem.

---

## Dependency chain

```text
PV-1                          CLOSED / PASS
PV-2                          CLOSED / PASS
PTI Layer A                   CLOSED / PASS
PAP Contract                  FROZEN / PASS
PTI Layer B                   CLOSED / PASS
Device-Side Operational Est.  CLOSED / PASS

Production Verifier Wiring    CLOSED / PASS
  see [PW Narrow Authorization](./0057-production-verifier-wiring-narrow-authorization.md)

Production Trust Integration
  IMPLEMENTATION CONFORMANCE    PASS

Track C Field Validation      NEXT / OPEN
  see [Track C Case A Run Card](../analysis/adr0057-track-c-production-convergence-field-validation-run-card.md)
ADR-0057 COMPLETE             NO
```

---

## Authorization decision (record)

```text
Decision:
  AUTHORIZE WITH TIGHTENING

Tightenings:
  PW-IA-T1 Trust Readiness / Re-evaluation
  PW-IA-T2 Unique Production Verifier Selection
  PW-IA-T3 Single Promotion Seam

Implementation start:
  GRANTED

Conformance:
  PASS (2026-08-31)
```

---

## Implementation artifacts

| Artifact | Role |
|----------|------|
| `wiring/GbcProductionTrustComposition.kt` | Production composition root |
| `wiring/GbcTrustWiring.kt` | `ProductionGbcTrustWiring` / `TestGbcTrustWiring` split |
| `TalkbackCoordinator.kt` | Production wiring injection; VB sole promotion seam |
| `TalkbackRuntimeFactory.kt` | Default production trust persistence under `filesDir/gbc-trust/` |
| `wiring/Adr0057ProductionVerifierWiringFixturesTest.kt` | PW-EG1..6 harness |

---

## PW-EG conformance record

| Gate | Result |
|------|--------|
| PW-EG1 | PASS — production lookup + production verifier |
| PW-EG2 | PASS — no injectable fallback in production graph |
| PW-EG3 | PASS — T0→T1→T2; UNVERIFIABLE recoverable; no authority window |
| PW-EG4 | PASS — verification failure does not erase good Current |
| PW-EG5 | PASS — Coordinator orchestration/effect boundary preserved |
| PW-EG6 | PASS — persisted anchor → full production chain → GBC |

---

## Authorization decision template (superseded)

```text
Decision:
  [ ] AUTHORIZE
  [ ] AUTHORIZE WITH TIGHTENING
  [ ] REJECT

Tightenings (if any):


Implementation start:
  [ ] GRANTED
  [ ] WITHHELD
```
