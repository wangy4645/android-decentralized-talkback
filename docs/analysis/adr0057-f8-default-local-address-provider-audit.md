# ADR-0057 F8b — Default-Local-Address Provider Audit

**Status:** ROOT CAUSE CLOSED · F8-FIX-1 AUTHORIZED  
**Authoritative field run:** `logs/adr0057-track-c-case-a-20260902-150414/`  
**Prior:** [ANY-address materialization](./adr0057-f8-any-address-materialization-audit.md)  
**Fix authorization:** [F8-FIX-1](./adr0057-f8-fix1-same-network-materialization-narrow-authorization.md)

---

## 1. Frozen posture

```text
F8:
  CONTRACT RETAINED
  FIELD ROOT CAUSE: PROVEN

F8b:
  ROOT CAUSE CLOSED

Root cause:
  WebRTC default-route-based local-address discovery
  is semantically insufficient for Talkback-selected MANET Network.

Not a defect in:
  A2 Java bridge
  NetworkMonitor
  native network map
  interface enumeration
  android_setsocknetwork
  Network::GetBestIP
  provider/cache wiring
  candidate filter

F9:
  NOT OPEN
```

---

## 2. Field adjudication (run 150414)

| Device | QUERY (v4) | SET cache | GET | materialization | GENERATED LAN |
|--------|------------|-----------|-----|-----------------|---------------|
| M01 | connectResult=0 → 192.168.10.26 | ipv4=192.168.10.26 | success=38 | materialized=38 | lan=38 |
| M02 | connectResult=-1 → none | ipv4=empty | fail=41 | lookup_failed=41 | lan=0 |
| M03 | connectResult=-1 → none | ipv4=empty | fail=36 | lookup_failed=36 | lan=0 |

M02/M03 concurrently: `networkIP=192.168.10.23/.24`, `F8_SOCKET_BIND networkBindResult=0`.

**Verdict:** `query_cache_empty_lookup_failed` — semantic gap, not provider wiring defect.

---

## 3. Static mechanism (pin @ b831eb81)

### Provider injection

```text
BasicNetworkManager / NetworkManagerBase
  → CreateNetworks / ConvertIfAddrs
  → network->set_default_local_address_provider(this)
```

### Cache population (`UpdateNetworksOnce`)

```text
QueryDefaultLocalAddress(AF_INET)   // UDP connect to public IPv4
QueryDefaultLocalAddress(AF_INET6)  // UDP connect to public IPv6
  → set_default_local_addresses(ipv4, ipv6)
```

### Consumption (`MaybeSetDefaultLocalAddress`)

```text
Network()->default_local_address_provider()->GetDefaultLocalAddress(family, &addr)
  → NetworkManagerBase::GetDefaultLocalAddress
  → returns cached default_local_ipv4_address_ / ipv6_
```

**Confirmed:** M01 succeeds because default-route probe succeeds; M02/M03 fail because MANET has no usable default route — not ABI-specific.

---

## 4. Trace events

| Event | Source |
|-------|--------|
| `F8_QUERY_DEFAULT_LOCAL` | `QueryDefaultLocalAddress` |
| `F8_SET_DEFAULT_LOCAL` | `UpdateNetworksOnce` |
| `F8_GET_DEFAULT_LOCAL` | `GetDefaultLocalAddress` |
| `F8_DEFAULT_LOCAL_ADDRESS` | `MaybeSetDefaultLocalAddress` |

Post F8-FIX-1: branch `fallback_same_network_best_ip` when narrow fallback applies.

---

## 5. Decision tree (closed)

```text
provider present; F8_QUERY_DEFAULT_LOCAL connect fails on M02/M03
  → QueryDefaultLocalAddress projection gap (default-route probe)
  → F8-FIX-1 narrow same-Network GetBestIP fallback AUTHORIZED

provider present; query succeeds but read fails
  → provider/cache projection bug (NOT observed)
  → fix projection; NO GetBestIP fallback

provider present; query succeeds; returned != GetBestIP()
  → intentional default-route semantics
  → retain provider path (M01)
```

---

## 6. STOP (unchanged scope)

NO F9 · NO A1 · NO synthetic candidate · NO filter bypass · NO global provider rewrite
