# ADR-0057 F8b — UDPPort ANY-Address Materialization Audit

**Status:** AUTHORIZED · TRACE-ONLY · NO BEHAVIOR FIX  
**Parent field run:** `logs/adr0057-track-c-case-a-20260902-140751/`  
**Prior audit:** [F8b host candidate emission](./adr0057-f8-candidate-emission-audit.md)

---

## 1. Frozen posture (post 140751)

```text
F8:
  CONTRACT RETAINED
  A2 Java propagation: FIELD PROVEN / CLOSED
  Native NetworkMonitor / map / enum / bind: FIELD PROVEN / CLOSED
  Candidate filter: BEHAVIOR CORRECT
  FIELD OBJECTIVE: FAIL / OPEN

F8b:
  ROOT SEAM CONFIRMED

Residual:
  UDPPort local-address materialization
  specifically:
    bound ANY address
    → MaybeSetDefaultLocalAddress / equivalent
    → concrete wlan0 local address

Observed failure:
  0.0.0.0:<port>
  remains ANY
  → candidate created as ANY
  → correctly rejected by candidate filter

F9:
  NOT OPEN
```

**STOP:** F8 contract rewrite · A1 · registry/bridge · socket binding behavior change ·
synthetic candidate · candidate rewrite · STUN/TURN · emit_local_for_anyaddress toggle as fix.

**Key inequality (field-proven):**

```text
android_setsocknetwork SUCCESS
!=
socket local address becomes wlan0 IP
```

**Candidate filter:** stop auditing — `any_ip` reject is consequence, not root cause.

---

## 2. Source mechanism (static, to be field-proven)

`PhysicalSocket::Bind` (WebRTC pin):

```text
BindSocketToNetwork(requestedIP) == SUCCESS
  → deliberately rewrite bind address to GetAnyIP()
  → ::bind(0.0.0.0, port)
  → getsockname → 0.0.0.0:port
```

Then `UDPPort::MaybeSetDefaultLocalAddress` is the **only** path that can restore a
concrete host IP before `FinishAddingAddress`.

`emit_local_for_anyaddress` is a **mechanism variable** — observe, do not change.

Irony to verify (not yet named as root cause): A2/network-binder **SUCCESS** may be
exactly what forces the ANY path that M01 (v7a) may avoid if binder fails / differs.

---

## 3. Audit questions (three only)

1. After UDP create/bind: what was `bind()` request, what did `getsockname()` return?
2. Was `MaybeSetDefaultLocalAddress` called; which branch; what default IP?
3. Same path on v7a vs arm64: ANY → concrete vs ANY → ANY?

---

## 4. Trace events (narrow)

| Event | Source | Fields |
|-------|--------|--------|
| `F8_SOCKET_BIND` | `PhysicalSocket::Bind` | requestedLocalAddress, networkBindResult, effectiveBindAddress, bindResult, getsockname |
| `F8_SOCKET_GETSOCKNAME` | after successful `::bind` | returnedLocalAddress, stage |
| `F8_DEFAULT_LOCAL_ADDRESS` | `MaybeSetDefaultLocalAddress` | before, candidateDefaultAddress, after, branch, emitLocalForAnyAddress, networkName, networkIP |
| `F8_PORT_ADDRESS_FINAL` | `OnLocalAddressReady` pre-`AddAddress` | address, networkName, networkIP, emitLocalForAnyAddress |

`networkBindResult` codes (`NetworkBindingResult`):

| Value | Meaning |
|------:|---------|
| 0 | SUCCESS |
| -1 | FAILURE |
| -2 | NOT_IMPLEMENTED |
| -3 | ADDRESS_NOT_FOUND |
| -4 | NETWORK_CHANGED |
| -99 | binder_not_attempted (trace sentinel) |

Branches for `F8_DEFAULT_LOCAL_ADDRESS`:

```text
skip_not_any | skip_emit_disabled | skip_no_provider
| provider_nil | provider_failed | materialized
```

---

## 5. Decision tree

```text
bind(requested=192.168.10.x)
→ networkBindResult=SUCCESS
→ effectiveBindAddress=0.0.0.0
→ getsockname=0.0.0.0
    = expected post-binder rewrite (confirm field)

MaybeSetDefaultLocalAddress NOT entered / skip_*
    = control-flow / emit_local_for_anyaddress / provider gap

candidateDefaultAddress=192.168.10.x but after still ANY
    = materialization logic defect

candidateDefaultAddress ANY / nil / wrong IP
    = GetDefaultLocalAddress projection gap
      (note: provider returns default-route IP, NOT Network::GetBestIP)

after becomes 192.168.10.x
→ F8_PORT_ADDRESS_FINAL still ANY
    = later overwrite / wrong variable
```

---

## 6. ABI compare (high-value)

Prefer shipping **same materialization traces in armeabi-v7a** so M01 can show:

```text
v7a:  ANY → concrete 192.168.10.26
arm64: ANY → ANY
```

First diverging event decides runtime logic vs compile/artifact difference.
Do **not** name ABI bug until that branch exists.

---

## 7. Authorization

```text
F8b root seam: CONFIRMED
Next trace-only audit: AUTHORIZED
Behavior fix: NOT AUTHORIZED YET
Scope: UDPPort ANY-address materialization only
```

---

## 8. Build / field

```text
1. WSL rebuild AAR (arm64; optionally also v7a overlay)
2. APK + install M01/M02/M03
3. Case A collectors (observe includes F8_SOCKET_ / F8_DEFAULT_ / F8_PORT_ADDRESS_)
4. scripts/adr0057-f8-materialization-adjudicate.ps1 -LogDir <dir>
```
