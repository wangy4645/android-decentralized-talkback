# Phase A Implementation Checklist

**ID:** conference-native-failure-containment-phase-a-implementation-checklist  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Implementation Checklist (not ADR · not authorization)

**Parent:** [Phase A Authorization](./conference-native-failure-containment-phase-a-authorization.md) v0.2 · [Failure Domain Contract](./conference-failure-domain-contract.md)

**Status:** Reference only. Implementation remains **BLOCKED** until Phase A Authorization is **ACCEPTED**.

---

## Before opening PR

```text
[ ] Phase A Authorization status is ACCEPTED (not PROPOSED only)
[ ] PR tagged: Phase A · CFC-1 PARTIAL · E+D only
[ ] Posture block appended (Acceptance 1 HOLD · L3/L4 OUT OF SCOPE)
[ ] No B2-1 lease / admission / domain lifecycle semantic change
[ ] No topology mutation (ActualMediaEdgeSet · meshGeneration · anchor)
[ ] No recovery obligation / completion predicate change
[ ] No WebRtcSharedFactory / SharedLocalAudio / Factory / ADM change
[ ] No D-β / D-γ / native partition / ConferenceAudioBus work
[ ] PR title/body avoids: P0.1g pass · native isolation fix · cross-edge SRD independence
```

---

## Code review

### L1 / L2 separation (I1 — must unit-test)

```text
[ ] Cause edge: EDGE_FAILED (L1)
[ ] Impact edge: DOMAIN_BLOCKED (L2) — NOT EDGE_FAILED
[ ] Peer-caused block on impact edge never classified as self EDGE_FAILED / ICE_FAILED
```

### L1 classifier (AUTH-PA-1)

```text
[ ] Runtime symptom (SRD_TIMEOUT, HANGING_OBSERVED, etc.) → classifier → EDGE_FAILED
[ ] Symptom not emitted directly as EDGE_FAILED terminal
```

### DOMAIN_BLOCKED attribution (AUTH-PA-3 / I7 / I8)

```text
[ ] Full tuple: impactEdgeKey, runtimeDomainRef, causeEdgeKey, causeFact,
    contentionKind, blockReason, generationScope, causePhase
[ ] causeFact = LEASE_HELD_BY_CAUSE | NATIVE_DOMAIN_OBSTRUCTED (not LEASE_BUSY alone)
[ ] causeEdgeKey from holder attribution or native observation — not roster/UI guess
[ ] runtimeDomainRef explicit in telemetry
```

### Cause → impact chain (AUTH-PA-5)

```text
[ ] LEASE_BUSY consumed as input only — not listed as causeFact
[ ] Auditable ordering: cause fact → DOMAIN_BLOCKED on impact edge
```

### Projection vs topology (I4 / I5 / I6)

```text
[ ] L-participant: DOMAIN_BLOCKED → impact participant not CONNECTING / invisible
[ ] No automatic Conference DEGRADED on DOMAIN_BLOCKED alone
[ ] Participant projection may change; topology truth does not
[ ] No new topology / recovery authority from failure classification
```

### B2-1 firewall

```text
[ ] Phase A classifies B2-1 facts — does not redefine lease behavior to ease DOMAIN_BLOCKED
```

---

## Tests

### Scenario E

```text
[ ] ConferenceEdgeKey cause edge reaches explicit EDGE_FAILED after classifier
[ ] No indefinite invisible CONNECTING on cause edge
[ ] Self-attributed — cause edge not DOMAIN_BLOCKED for own failure
```

### Scenario D

```text
[ ] ConferenceEdgeKey(M01,M03) cause observable (HANGING_OBSERVED and/or EDGE_FAILED + causeFact)
[ ] ConferenceEdgeKey(M01,M04) DOMAIN_BLOCKED with causeEdgeKey = ConferenceEdgeKey(M01,M03)
[ ] Impact participant projected — not CONNECTING
[ ] ConferenceEdgeKey(M01,M02) may remain operational
```

---

## PASS claim (field or desk)

**Allowed:**

```text
Phase A PASS
CFC-1 PARTIAL PASS (Scenario E ∧ D)
P0.1g Acceptance 1 remains HOLD — ORIGINAL
```

**Forbidden:**

```text
CFC-1 FULL PASS · P0.1g PASS · Acceptance 1 PASS
native isolation proven · fault containment complete · P0.1g closed
```

---

## Audit quick gate

Any YES → block merge or invalidate PASS:

```text
[ ] Q-PA5  Impact edge EDGE_FAILED for peer-caused block?
[ ] Q-PA6  B2-1 semantics changed?
[ ] Q-PA7  Topology / recovery / MediaUsable introduced?
[ ] Q-PA8  Auto DEGRADED on DOMAIN_BLOCKED alone?
[ ] LEASE_BUSY used as sole causeFact for DOMAIN_BLOCKED?
```

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Initial checklist — PR / review / test / PASS gates |
