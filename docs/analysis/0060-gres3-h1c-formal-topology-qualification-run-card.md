# G-RES-3-H1c — Formal Topology Qualification Run Card

## Status

```text
Phase:              H1c — Formal Topology Qualification
runClass:           QUALIFICATION (NOT qualifying)
Series status:      FAIL / STOP (lightweight #1 MAX 10.795ms)
Parent:             G-RES-3-H1 evidence harness
Prerequisites:      H1a COMPLETE · H1b COMPLETE
Formal 3-run:       HOLD
G-RES-3 evidence:   PENDING
Next track:         G-RES-3 E2 execution-model adjudication
```

---

## Purpose

Answer one question only:

```text
Can this 9-target topology be used for formal G-RES-3 evidence?
```

H1c is **not** formal capacity evidence. It validates:

1. Topology legality (9 distinct identities, real WLAN egress, no loopback/multicast)
2. Harness runtime on real network path (send path, sinks bound, bundle complete)
3. Whether formal `3×QUALIFYING @ N=9` is worth attempting vs remediation first

---

## Scope boundary

| In scope | Out of scope |
|----------|--------------|
| Topology preflight + short measurement | 600s qualifying runs |
| Manifest topology freeze | ProvenNMax claim |
| C3 projection (informational) | C3 threshold change |
| Network observability capture | G-RES-3 PASS/FAIL |

```text
fanout P99/MAX over C3 limits in H1c → C3_PROJECTION=LIKELY_FAIL
                                    ≠ G-RES-3 FAIL
```

---

## Frozen phase timings (H1c)

```text
WarmupDurationSec        = 60
MeasurementDurationSec   = 90        (60–120s window; default 90)
CooldownDurationSec      = 30
runClass                 = QUALIFICATION
```

---

## Topology selection priority

```text
9 distinct IP
  > multiple devices + IP alias / capacity sink host
  > 1 IP + 9 ports (loopback lab only — NOT formal-eligible)
```

Lab default (3 Android devices): **M03 sender DUT** → **M01 + M02 capacity sinks**
(6 endpoints on M01 IP, 3 on M02 IP = 9 legs, 2 distinct receiver IPs).

---

## Preflight (before measurement)

```text
targetCount = 9
all receiverModuleId distinct
all (receiverIp, receiverPort) distinct
all remote sinks actively bound
no target resolves to loopback
no multicast destination
sender route via intended wlanInterface
thermal preflight observable (reuse H1a)
```

Record (evidence-only, not C3 gate):

```text
per-target route / interface hint
WiFi SSID / BSSID
sender local IP
neighbor snapshot (best-effort)
```

---

## Adjudicator outputs (H1c)

```text
TOPOLOGY_VALIDITY:           PASS | FAIL
HARNESS_RUNTIME_VALIDITY:    PASS | FAIL
FORMAL_EVIDENCE_TOPOLOGY:    ELIGIBLE | NOT_ELIGIBLE
C3_PROJECTION:               LIKELY_PASS | LIKELY_FAIL   (informational)
reasons[]
```

```text
FORMAL_EVIDENCE_TOPOIGY=ELIGIBLE requires:
  TOPOLOGY_VALIDITY=PASS
  HARNESS_RUNTIME_VALIDITY=PASS
  topologyClass != loopback
  runClass=QUALIFICATION
```

---

## Manifest schema (frozen)

See `config/gres3-topologies/README.md` and bundle `manifest.json` field `phase=H1c`.

```json
{
  "harness": "G-RES-3-H1",
  "phase": "H1c",
  "runClass": "QUALIFICATION",
  "topologyId": "gres3-h1c-lab-m03-sender-v1",
  "topologyClass": "wlan-capacity-sink",
  "senderDut": {
    "deviceLabel": "M03",
    "deviceSerial": "MDX0220416001963",
    "moduleId": "MDX0220416001963",
    "wlanInterface": "wlan0",
    "localIp": "192.168.10.24"
  },
  "targets": [
    {
      "receiverModuleId": "M01-R01",
      "receiverIp": "192.168.10.22",
      "receiverPort": 47001,
      "sinkIdentity": "M01:47001",
      "sinkHostLabel": "M01"
    }
  ],
  "multicastLegCount": 0,
  "distinctReceiverIpCount": 2
}
```

---

## Execution

```powershell
# 1. Start capacity sinks on M01 + M02
.\scripts\adr0060-gres3-h1c-start-sinks.ps1

# 2. Run M03 sender qualification
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03

# 3. Adjudicate harness + topology validity only
.\scripts\adr0060-gres3-h1c-adjudicate.ps1 -LogDir logs\gres3-h1c-qual-<runId>
```

---

## Decision tree after H1c

```text
FORMAL_EVIDENCE_TOPOLOGY=ELIGIBLE + C3_PROJECTION=LIKELY_PASS
  → proceed to 3×QUALIFYING @ N=9 (600s)

FORMAL_EVIDENCE_TOPOLOGY=ELIGIBLE + C3_PROJECTION=LIKELY_FAIL
  → capacity remediation first; do NOT waste 3×11.5min runs

TOPOLOGY_VALIDITY=FAIL
  → fix topology / sinks / routing; rerun H1c
```

---

## References

- `docs/analysis/0060-gres3-capacity-envelope-contract-outline.md`
- `config/gres3-topologies/gres3-h1c-lab-m03-sender-v1.json`
- `scripts/adr0060-gres3-h1c-*.ps1`
