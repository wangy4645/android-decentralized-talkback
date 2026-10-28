# Case B Stimulus Review

**Status:** `CLOSED / FROZEN` — stimulus shape retained; causal validity **OPEN**  
**Closed:** 2026-08-04 with Attempts #1–#6  
**Normative stimulus id:** `S1_LOCAL_QUALIFICATION_REPAIR`  
**Causality:** [l15-prr-case-b-stimulus-causality-review.md](./l15-prr-case-b-stimulus-causality-review.md)  
**Acquisition:** [l15-prr-case-b-evidence-acquisition-v2.md](./l15-prr-case-b-evidence-acquisition-v2.md)  
**Preflight:** [l15-prr-case-b-field-preflight-v1.md](./l15-prr-case-b-field-preflight-v1.md)  
**Witness:** [l15-prr-case-b-branch-entry-witness.md](./l15-prr-case-b-branch-entry-witness.md)  
**Gate:** [l15-prr-case-b-field-admission-gate.md](./l15-prr-case-b-field-admission-gate.md)

```text
CASE_B_STIMULUS_STATUS
S0       REJECTED
S-PEER   REJECTED (NOT_LOCAL_QUALIFICATION_TRIGGER)
S1       FROZEN as S1_LOCAL_QUALIFICATION_REPAIR
S2/S3    deferred

S1 causal validity OPEN (see causality review)
Field Attempt #7 NOT AUTHORIZED
No production code change
```

## Frozen shape — S1_LOCAL_QUALIFICATION_REPAIR

| Field | Content |
|-------|---------|
| **Id** | `S1_LOCAL_QUALIFICATION_REPAIR` |
| **Topology** | `M02 ↔ M03` only (M01 not in conference) |
| **Roles** | DUT=M03 WiFi ON · Peer=M02 WiFi OFF ≥35–45s · then ON |
| **Scope** | validate **local** qualification timeout / L.1.5 admission |
| **Non-goal** | mesh peer resilience · membership convergence · peer edge recovery |
| **Expected** | local qualification wait/timeout → REPAIR_* → PRR (pair transport) |
| **Evidence** | UNQUALIFIED/TIMEOUT → LINK_QUALIFICATION_REPAIR_REQUESTED on DUT |
| **Ecology boundary** | pair transport repair ≠ 3-node mesh claim |
| **Abort (formal)** | DUT NETWORK_LOST→Branch A · or M01 still providing inbound |
| **Pre-attempt abort** | see Preflight v1 (`PRE_ATTEMPT_ABORT`) |

## Rejected (unchanged)

S0 · S-PEER (`PEER_EDGE_NOT_READY ≠ LOCAL_TRANSPORT_UNQUALIFIED`)

## Deferred

S2 all-peer silence · S3 harness inbound suppress

## Constraint

```text
PEER path failure ≠ local qualification loss
S1 result MUST NOT be extrapolated to mesh PRR
```