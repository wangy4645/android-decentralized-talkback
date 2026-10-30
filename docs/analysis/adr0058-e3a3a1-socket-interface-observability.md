# ADR-0058 E3a.3a.1 — Socket / Interface Observability

## Status

```text
SCOPE:     observability only — no bind/join/send behavior change
NEXT:      M01→M02 classification rerun
THEN:      E3a.3a.2 M03 receiver repair
```

## JSON + logcat fields (v3 transport facts)

| Field | Sender | Receiver |
|-------|--------|----------|
| `requestedNetworkInterfaceName` | selected iface arg | selected iface arg |
| `socketNetworkInterfaceName` | socket NIC name | socket NIC name |
| `socketNetworkInterfaceIndex` | socket NIC index | socket NIC index |
| `socketLocalIpv4` | local IPv4 (falls back to NIC IPv4 if bind is `::`) | same |
| `networkInterfaceIpv4` | NIC IPv4 from interface enumeration | same |
| `socketBindAddress` | bind host | bind host |
| `socketLocalPort` | bind port | bind port |
| `destinationMulticastAddress` | ✓ | — |
| `destinationMediaPort` | ✓ | — |
| `joinedMulticastAddress` | — | ✓ |
| `joinedMediaPort` | — | ✓ |
| `joinedNetworkInterfaceName` | join iface (if joined) | join iface |

Logcat:

```text
PROBE_TRANSPORT ...
PROBE_TRANSPORT_END ...
```

Historical runs without these fields → adjudicator shows `UNKNOWN`.

## Classification rerun

```powershell
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M01toM02 -SkipInstall -Iface wlan0
```

If both sides show `wlan0 / 192.168.10.x / 46999` and delivery remains 0% → wrong-interface hypothesis largely excluded → proceed 3a.2.
