# ADR-0058 E3a.3d — M02 Device-Side Multicast Ingress Classification

## Status

```text
Prerequisite: E3a.3c CLOSED — AP/mesh transient forwarding weakened
Primary suspect: M02 device-side multicast ingress filtering / driver / firmware state
Media engine: NOT AUTHORIZED
No further AP reboots / M02 reboots without new ADR
```

## Question

**Do multicast packets reach M02 wlan0 but fail to reach the app socket, or never arrive at the NIC?**

## Frozen execution order

| Step | Action | Purpose |
|------|--------|---------|
| **3d.1** | `/proc/net/igmp` sample pre / during / post M03→M02 probe | kernel membership vs `joinSucceeded` |
| **3d.2** | M02 wlan0 pcap (if root tcpdump available) + same probe | air/NIC vs socket path |
| **3d.3** | Route by classification | vendor workaround / ADR only on evidence |

## Key observation (hypothesis)

```text
joinSucceeded=true (Java) BUT /proc/net/igmp wlan0 missing 239.255.42.99
→ Java joinGroup() OK, kernel/NIC membership not held
```

Group `239.255.42.99` in `/proc/net/igmp` hex: `632AFFEF` (verify per device endian).

## 3d.1 — IGMP timeline

During M03→M02 probe (receiver lead 5s + sender 60s + tail 10s):

- T0: pre-probe baseline
- T+5s..T+75s: sample every 3s while receiver instrument running
- Correlate timestamps with probe JSON `startedAtMs` / `joinSucceeded`

Store: `IGMP_TIMELINE.txt`

## 3d.2 — wlan0 pcap

```bash
# M02 (userdebug + su)
su 0 tcpdump -i wlan0 -w /sdcard/adr0058-e3d-m02.pcap \
  'udp and dst host 239.255.42.99 and dst port 46999'
```

Run concurrently with M03→M02 probe. Compare:

- pcap packet count vs `receivedCount`
- **Heisenbug rule:** if pcap start causes recovery, mark run HEISENBUG — not normal evidence

## Verdict routing

| pcap | app receive | igmp group | Classification |
|------|-------------|------------|----------------|
| YES | NO | missing | Java join / kernel membership seam |
| YES | NO | present | socket/filter path above IGMP |
| NO | NO | missing | driver/firmware/RX filter or AP STA downlink |
| NO | NO | present | inconsistent — investigate filter programming |
| recovery on pcap | — | — | HEISENBUG — separate track |

## Constraints

- No M02 reboot / WiFi toggle / AP changes
- `SkipForceStopModules M02` on probe runs
- PLC/jitter gates not in scope (ingress hard failure)
