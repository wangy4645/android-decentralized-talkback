# ADR-0058 E3a — Multicast Underlay Reality Probe (Status)

## E3a — Reachability (E3a.1 CLOSED)

```text
STATUS:
  PARTIAL PASS / UNDERLAY MULTICAST REACHABILITY ESTABLISHED

PROVEN:
  M01 → M02 multicast delivery exists (~79%, run 173813)
  M02 → M01 multicast delivery exists (~89%, run 173610)
  wlan0 join path works on both devices
  underlay is not a symmetric zero-delivery multicast domain

NOT YET PROVEN:
  delivery quality sufficient for media runtime
  loss burst length stays within PLC assumptions
  jitter stays within 120 ms playout envelope
  multicast fan-out works reliably to a third node
  M03 receiver path is healthy

INVALID RUNS:
  172740 M01→M02 0% — superseded by 173813 (transient / state-dependent)
  174057 M01→M03 — INVALID FOR UNDERLAY ADJUDICATION
    reason: receiver probe did not complete / no JSON artifact
    class: probe/runtime execution failure (NOT multicast delivery failure)

GATE:
  Media Engine / Opus / AudioTrack / Meeting integration = NOT AUTHORIZED
  Unlock per ADR-0060 G-RES-1..5 (correctness fallback + continuous capability + N_max)
  Multicast underlay PASS is NOT a correctness gate (optimization qualification only)
```

## E3a.2a — Probe Observability Completion (CLOSED)

v2 receiver JSON adds (observability only):

- `interArrivalGapOver120MsCount`
- `maxInterArrivalGapMs`

See `adr0058-e3a2a-probe-observability-completion.md`. v1 runs show `UNKNOWN` in adjudicator.

## E3a.2 — Delivery Quality Adjudication (CLOSED — AMBER, v1 JSON)

| Question | M01→M02 (173813) | M02→M01 (173610) |
|----------|------------------|------------------|
| max consecutive loss | **4** (≤5 OK) | **10** (>5 FAIL) |
| gap P95 / P99 (ms) | 95 / **102** | 97 / **129** |
| delivery | 79.3% (observability) | 88.7% (observability) |
| gaps >120 ms count | UNKNOWN (v1) | UNKNOWN (v1) |

**E3a.2 verdict: AMBER** — gates: burst + P99 gap only (delivery % not a frozen GREEN gate). Underlay tuning before media engine.

## E3a.3 — Phase 0 Baseline (STOP after R2)

```text
STATUS: STOP — repeatability objective satisfied for current failure mode

R1/R2 (2026-09-03, underlay_change=NONE):
  M01→M02: 0% x2 (join OK; sender 3000 sent; receiver 0)
  M02→M01: GREEN x2 (96–99%, burst≤3, P99≤100)

FINDING:
  CONFIRMED repeatable directional zero-delivery
  Not symmetric zero-delivery domain

Artifacts:
  logs/adr0058-e3a3-phase0-r1-20260903-085706/
  logs/adr0058-e3a3-phase0-r2-20260903-090154/
```

Remediation run card (`adr0058-e3a3-underlay-remediation-run-card.md`) **deferred** until E3a.3a classification CLOSED.

## E3a.3a — Directional Failure Classification (ACTIVE)

See `adr0058-e3a3a-directional-failure-classification.md`.

**Frozen execution order:**

```text
3a.1 Socket/interface observability  → CLOSED
3a.1 rerun M01→M02 classification    → CLOSED (wrong-iface excluded)
3a.2 M03 receiver probe repair       → CLOSED
3a.3 cross-device matrix             → CLOSED (see CLASSIFICATION_MATRIX.txt)
```

**E3a.3 classification result:** M02 receiver ingress primary suspect — not M01 global sender failure.

```text
M01→M03 = 93% PASS
M03→M02 = 0%  (join OK)
M01→M02 = 0%  (prior)
M02→M01 = GREEN (prior)
```

Next: M02 ingress investigation (single-variable): `adr0058-e3a3b-m02-ingress-run-card.md`

```text
M01→M03 = 93% PASS
M03→M02 = 0%  (join OK)
M01→M02 = 0%  (prior)
M02→M01 = GREEN (prior)

Classification: M02 receiver ingress primary suspect
```

## E3a.3b — M02 Ingress State-Loss (CLOSED)

See `adr0058-e3a3b-m02-ingress-run-card.md`.

```text
CLASSIFICATION (FROZEN):
  E3a.3b — M02 Multicast Ingress State-Loss

CONFIRMED:
  M02 multicast ingress can establish but is non-persistent
  Once lost: WiFi toggle / process reset do NOT recover
  M02 reboot once triggered recovery (0% → brief window, then lost again in 3R)

NOT YET PROVEN:
  root cause device-side vs AP/per-client forwarding-side

Step 3 fingerprint (not "19.3% random loss"):
  seq 0 start → ~12s receive window → terminalTailMissingPackets=2407 (~48.1s @ 50pps)
  interior maxConsecutiveLoss=1

Observability (adjudicator; NOT frozen gate change):
  interiorConsecutiveLoss vs terminalTailMissingPackets — see adjudicate.ps1 G2 OBS lines

Artifacts:
  logs/adr0058-e3a3b-m02-ingress-step3-reboot-20260903-101545/
  logs/adr0058-e3a3b-m02-ingress-step3r-repeat-20260903-102300/
```

## E3a.3c — M02 Ingress State Ownership (CLOSED)

See `adr0058-e3a3c-m02-ingress-state-ownership-run-card.md`.

```text
AP/mesh reboot (field, BSSID f0:b4:29:55:a6:bd) + forced reassoc:
  TEST M03→M02: STILL 0% (0/2999) join OK  [105615]
  CTRL M02→M01: TX healthy (85.2%, burst=5, P99=100) [105736]
  prior attempt 103154: INVALID (no AP flap)

VERDICT: STILL_ZERO
  AP transient forwarding-state weakened
  M02 device/driver ingress filtering stronger candidate
  caveat: AP reboot ≠ sole root-cause proof either way; Step1 WiFi toggle
          already showed plain reassoc insufficient — recovery would have
          biased AP/mesh; non-recovery biases device-side
```

Artifacts: `logs/adr0058-e3a3c-ap-reboot-retry-20260903-105612/`

## E3a.3d — M02 Device-Side Ingress (CLOSED for observation)

See `adr0058-e3a3d-m02-device-ingress-run-card.md`.

```text
E3a.3d.1  PASS — kernel IGMP membership confirmed
E3a.3d.2  INCONCLUSIVE — device pcap unusable
E3a.3d.3  PASS — MTK RX/filter path abnormal strongly supported
E3a.3d.4  INCONCLUSIVE — Step3 ~12s packet-filter transition NOT retained

FROZEN:
  M02 device-side WiFi multicast RX/filter path abnormal — HIGH
  exact triggering mechanism — NOT YET PROVEN
```

## E3a.3e — M02 Multicast RX Minimal Intervention A/B (CLOSED)

See `adr0058-e3a3e-m02-multicast-rx-ab-run-card.md`.

```text
ONE variable per experiment. No AP / RTP / pacing / media engine.

A1 power_save OFF: NO_CHANGE (0/2999; CTRL 98.5% VALID)
A2 allmulticast ON: NO_CHANGE (0/2999; CTRL 95.8% VALID)
A3 STOP — do not stack; kernel allmulti + PS off both insufficient
```

Artifacts:
  logs/adr0058-e3a3e-a1-ps-off-20260903-111859/
  logs/adr0058-e3a3e-a2-allmulti-20260903-113408/

## Architecture decision — ADR-0060 (ACCEPTED / FROZEN)

See `docs/adr/0060-conference-media-transport-resilience-contract.md`.

```text
multicast-only as correctness foundation     REJECTED
multicast as optimization path               ACCEPTED (continuous liveness)
correctness path                             sender-side unicast fan-out
authority split                              receiver owns observation;
                                             sender owns delivery-mode execution
V1 replication                               sender-side only; NO relay / owner topology
N_max                                        MUST freeze; >= declared envelope fan-out demand

ADR-0059 role: optimization assumption only — NOT correctness dependency
Media Engine gate: G-RES-1..5 (fallback + liveness pub/consume + N_max envelope)
                  NOT "multicast underlay PASS"
```

E3a continues as optimization-path evidence only. 3f-1 does not block main line.

Profile 02 amendment (ADJUDICATION COMPLETE — C1–C8 corpus pending):
  outline: 0058-profile-02-amendment-unicast-fanout-outline.md
  A1–A10:  docs/analysis/0058-profile-02-a*-*.md
  closeout: 0058-profile-02-a10-acceptance-corpus-closeout.md
  Next: G-RES-3 evidence collection (C1–C4 adjudication COMPLETE)

## E3a.3f — Post-decision evidence tracks

### 3f-0 Product-control audit (CLOSED — read-only)

See `adr0058-e3a3f0-mtk-multicast-filter-product-control-audit.md`.

```text
VERDICT: no non-root shippable MTK multicast RX workaround found
MulticastLock (product API) already exercised — insufficient on M02 fault class
Privileged knobs (ALLMULTI, PS, RXFILTER shell, driver OID) → product value 0
Does NOT gate ADR-0060
```

### 3f-1 L2 domain replacement (AUTHORIZED — field, non-blocking)

See `adr0058-e3a3f1-l2-domain-replacement-run-card.md`.

```text
Purpose: liveness cache granularity (device vs device×network)
Does NOT gate multicast-only decision (REJECTED by ADR-0060)
Does NOT block Profile 02/03 amendment main line
```

Artifacts (3d):
  logs/adr0058-e3a3d-device-ingress-20260903-110153/
  logs/adr0058-e3a3d-wifi-vendor-obs-20260903-110932/
  logs/adr0058-e3a3d-step3-12s-correlation-20260903/

## E3a.2 — Historical adjudication (2026-09-02)

Authoritative bidirectional pair:

- Forward: `logs/adr0058-underlay-mcast-probe-20260902-173813/` (M01→M02)
- Reverse: `logs/adr0058-underlay-mcast-probe-20260902-173610/` (M02→M01)

```powershell
.\scripts\adr0058-underlay-mcast-probe-e3a2-adjudicate.ps1 `
  -ForwardLogDir logs\adr0058-underlay-mcast-probe-20260902-173813 `
  -ReverseLogDir logs\adr0058-underlay-mcast-probe-20260902-173610
```
