# ADR-0057 F8-FIX-1 — Same-Network Local Address Materialization Narrow Authorization

## Status

**Implementation Conformance: PASS · Field Validation: PASS · CLOSED** (2026-09-02)

```text
ADR-0057 / F8-FIX-1

Implementation Conformance: PASS
Field Validation: PASS
Status: CLOSED

F8:
  CONTRACT RETAINED
  ROOT CAUSE PROVEN
  FIX VERIFIED
  CANDIDATE-GENERATION LAYER CLOSED

F8b:
  ROOT CAUSE CLOSED

F8-FIX-1:
  FIELD PASS / CLOSED FOR THIS DEFECT

F9:
  NOT OPEN
```

**Retained (not modified by this fix):**

```text
QueryDefaultLocalAddress:     RETAINED
global default-local cache:   RETAINED
candidate filter:             RETAINED
synthetic candidate:          NOT INTRODUCED
cross-network discovery:      NOT INTRODUCED
A1:                           NOT NEEDED
F9:                           NOT OPEN
```

**Authoritative field run:** `logs/adr0057-track-c-case-a-20260902-152328/`

**Parents:**

- [F8b provider audit](./adr0057-f8-default-local-address-provider-audit.md) — field run `150414`
- [ANY-address materialization audit](./adr0057-f8-any-address-materialization-audit.md)
- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)

---

## Frozen causal chain

```text
Selected Network = wlan0 / 192.168.10.x
→ Network::GetBestIP() = concrete LAN IP
→ socket binds to same Android Network successfully
→ QueryDefaultLocalAddress uses default-route/public probe
→ MANET node has no usable default route
→ probe fails
→ global default-local cache empty
→ ANY cannot materialize
→ any_ip candidate rejected
```

Provider failure is **expected** under this network model, not a wiring defect.

---

## F8-FIX-1 contract

```text
F8-FIX-1 — Same-Network Local Address Materialization

Given:
  - UDPPort belongs to an admitted WebRTC Network N;
  - the socket local address is ANY;
  - emitLocalForAnyAddress=true;
  - default-local-address lookup cannot supply a concrete address;

Then:
  the port MAY materialize the local host address from N.GetBestIP()

IFF:
  1. GetBestIP is concrete / non-ANY;
  2. address family matches the port/socket family;
  3. the address belongs to the same Network N;
  4. N is the exact Network already admitted to this UDPPort;
  5. no cross-network/default-interface search is performed.

The resulting address enters the existing native host-candidate path.
```

**Semantic:** `ANY representation → already-known address of this exact Network`  
**Not:** `failed discovery → go discover some other usable IP`  
Therefore: **not** synthetic candidate.

---

## Implementation seam

`UDPPort::MaybeSetDefaultLocalAddress` (`p2p/base/stun_port.cc`)

```text
defaultLocal = provider->GetDefaultLocalAddress(family)

if concrete(defaultLocal):
    use defaultLocal                     // existing behavior retained

else if localAddress.IsAnyIP()
     && emitLocalForAnyAddress
     && concrete(network->GetBestIP())
     && SameFamily(...)
     && AddressBelongsToNetwork(...):
    use network->GetBestIP()             // F8-FIX-1 narrow fallback

else:
    retain existing behavior
```

Trace branch: `fallback_same_network_best_ip`

**Do not modify:** global `QueryDefaultLocalAddress` / default-local cache semantics.

---

## Explicitly forbidden

```text
NO:
  arbitrary interface enumeration fallback
  global "first non-loopback IP"
  cross-Network fallback
  signaling IP injection
  synthetic IceCandidate
  candidate rewriting after creation
  bypass candidate filter
  changing QueryDefaultLocalAddress semantics globally
  STUN/TURN
  ICE restart/retry changes
  A1
  F9
```

---

## Evidence gates (EG)

| ID | Requirement |
|----|-------------|
| EG1 | Provider success: existing behavior unchanged; fallback not entered (M01) |
| EG2 | Provider fail + valid same-Network GetBestIP: ANY → concrete Network IP (M02/M03) |
| EG3 | GetBestIP=ANY: no materialization |
| EG4 | Family mismatch: no materialization |
| EG5 | Address not attributable to same Network: no materialization |
| EG6 | Resulting candidate passes ordinary FinishAddingAddress/filter/surface path |
| EG7 | GPLB/GIE/IGIE/GIDR/GBC unchanged |

---

## Field close criteria (Case A)

```text
M02/M03:
  provider lookup_failed
  → F8 fallback selected (fallback_same_network_best_ip)
  → PORT_ADDRESS_FINAL = 192.168.10.23/.24
  → CANDIDATE_CREATED host 192.168.10.x
  → CANDIDATE_SURFACED
  → Java GENERATED 192.168.10.x
  → M02↔M03 ICE_CONNECTED (if transport permits)
```

`transmitReady` is **not** the F8 close gate.

If LAN candidate surfaced but M02↔M03 still do not connect, score F8 at candidate-generation layer only; do not expand fallback scope.

---

## Field evidence closure (run 152328)

```text
provider lookup_failed
→ same-Network GetBestIP fallback (fallback_same_network_best_ip)
→ ANY materialized to 192.168.10.23/.24
→ ordinary host candidate creation/filter/surface
→ Java GENERATED LAN candidate
→ M02↔M03 ICE_CONNECTED
```

M01: continued `branch=materialized` via provider — no fallback entered.

**F8 close gate:** candidate-generation / transport edge — **MET**.  
**Not an F8 gate:** `transmitReady` / Case A end-to-end completion.

---

## Scope boundary (post-close)

Do **not** infer ADR-0057 / Case A completion from F8 `ICE_CONNECTED` alone.  
Track C end-to-end close requires separate adjudication against original terminal gates:

```text
Generation convergence
→ GROUP control/bootstrap
→ GROUP_ACCEPT
→ correct PC/ICE lineage
→ ICE_CONNECTED
→ media readiness / transmitReady
→ bounded idle convergence
```

If `transmitReady` remains open after F8 close, treat as **post-F8 residual** in a new domain — **do not** expand Same-Network GetBestIP fallback.

---

## Build / field

```text
1. Rebuild hybrid AAR (pnsrd-inst-1 with F8-FIX-1)
2. Install M01/M02/M03
3. Case A field run
4. Verify EG1–EG7 from logs
```
