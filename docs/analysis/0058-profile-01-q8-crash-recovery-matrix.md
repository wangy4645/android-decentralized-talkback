# ADR-0058 Profile 01 Q8 — Crash / Recovery Acceptance Matrix

## Status

```text
Matrix:         FROZEN FOR PROFILE 01 Q8
Parent:         0058-implementation-profile-01-control-crypto.md
Implementation: NOT AUTHORIZED
```

This matrix freezes observable recovery results, not SQLite, WAL, file paths or
the Native Media Runtime implementation. Every future implementation test MUST
inject the named crash/corruption boundary and assert all three Q9 axes.

## Common invariants

Every case MUST prove:

```text
no uncommitted Fact becomes accepted or originated
no committed authority rolls back
no retired Package/key/Source becomes executable
no duplicate identity causes a second unwrap/activation/side effect
no media or network side effect occurs before durable commit
one failed Conference does not authorize partial recovery of that Conference
```

`VALID_PASS` requires both durable-state inspection and externally observable
side-effect evidence. A UI state or audible packet alone is not proof.

## Required cases

### CR-01 — Origin crash before durable commit

```text
Injection:   canonical Fact/Package built and signed; crash before Q8 commit
Authority:   no local originated/accepted authority
Durability:  NOT_COMMITTED
Execution:   GATED
Restart:     MUST NOT emit the uncommitted bytes or reuse their identity
```

### CR-02 — Origin commit succeeds, crash before first emission

```text
Injection:   crash after exact authored bytes + authority advance commit
Authority:   ACCEPTED locally as authored authority
Durability:  COMMITTED
Execution:   GATED until reconciliation
Restart:     relay the exact stored bytes/signature; never regenerate identity
```

### CR-03 — Commit and emission succeed, crash before media side effect

```text
Injection:   crash before key activation/group join/Source execution completes
Authority:   ACCEPTED
Durability:  COMMITTED
Execution:   initially GATED, then reconcile exactly once
Restart:     execute committed pending intent; do not re-originate authority
```

### CR-04 — Inbound validation succeeds, crash before accept transaction

```text
Injection:   signature/semantics valid; crash before C/P/A commit
Authority:   no local ACCEPTED verdict survives
Durability:  NOT_COMMITTED
Execution:   GATED
Restart:     sender/holder must re-provide; arrival memory is not authority
```

### CR-05 — Package transition crashes before ACTIVE commit

```text
Injection:   Package unwrap/GCM/commitment checks performed; crash before commit
Authority:   Package remains its previous ENCRYPTED_STAGED state
Durability:  no ACTIVE transition committed
Execution:   KEY_PENDING / GATED
Restart:     normal validation may run again; no media key was durably activated
```

Implementations SHOULD avoid decrypting before the commit decision is ready,
but plaintext that existed only in process memory is destroyed by the crash and
has no authority effect.

### CR-06 — Package ACTIVE commit succeeds, crash before media installation

```text
Injection:   P=ACTIVE and pending execution intent committed; crash before Native
Authority:   ACCEPTED / Package ACTIVE binding unchanged
Durability:  COMMITTED
Execution:   GATED during restore
Restart:     one RECOVERY_UNSEAL of exact retained Package, then reconcile media
Forbidden:   second Package accept, ledger advance or multi-key trial unwrap
```

### CR-07 — Termination commits, crash before Native cleanup

```text
Injection:   T + exact Termination + fences committed; media still allocated
Authority:   ACCEPTED Termination
Durability:  COMMITTED Tombstone
Execution:   TERMINATED
Restart:     Tombstone wins; stop send, leave group and clear memory before ready
Forbidden:   temporarily restoring the old Conference as ACTIVE
```

### CR-08 — Recovery Package targets a retired recipient key version

```text
Injection:   current Package was ACTIVE before crash; its RSA key is now retired
Authority:   Membership/Package authority remains accepted
Durability:  COMMITTED
Execution:   KEY_PENDING / GATED
Restart:     request Owner equivalent re-wrap or authoritative rekey
Forbidden:   old-key unwrap, trial-decrypt, Membership rollback
```

### CR-09 — SRTP replay checkpoint unavailable

```text
Injection:   C/P/A valid; matching M absent, corrupt or behind reserved fence
Authority:   control authority may restore
Durability:  COMMITTED with SRTP_FENCE_UNKNOWN evidence
Execution:   affected old Source QUARANTINED; unrelated valid Sources MAY run
Exit:        verifiably new Source incarnation or new mediaKeyEpoch only
```

### CR-10 — Tombstone and active Conference state disagree

```text
Injection:   valid-looking T coexists with executable ACTIVE C, or digests differ
Authority:   no new verdict manufactured
Durability:  RESTORE_PENDING / corruption evidence retained
Execution:   entire affected Conference GATED/TERMINATED fail-closed
Repair:      exact holder Facts or explicit operator action; no LWW
```

If a valid Tombstone independently verifies its Creation/Termination binding,
it is irreversible and prevents active restoration. Ambiguous/corrupt evidence
must not be repaired by choosing the active row.

### CR-11 — PackageLedger identity/binding mismatch

```text
Injection:   same packageIdentity maps to different digest/context or partial row
Authority:   KEY_PACKAGE_IDENTITY_CONFLICT or restoration integrity failure
Durability:  RESTORE_PENDING for the affected Conference
Execution:   relevant key and Conference GATED/QUARANTINED
Forbidden:   choose newest row, unwrap either candidate, delete conflict silently
```

### CR-12 — GlobalTrustStore vs Keystore mismatch

```text
Injection:   ACTIVE Profile fingerprint/version lacks matching purpose-bound alias
Authority:   existing accepted Conference Facts do not roll back
Durability:  G restore failure for affected local capability
Execution:   no new Fact origin and/or Package unwrap; existing in-memory media is
             unavailable after process restart
Repair:      Q7 explicit re-enrollment; no HELLO/TOFU repair
```

### CR-13 — Persistence capacity exhausted

```text
Injection:   next authority transaction cannot reserve durable capacity
Authority:   attempted advance not accepted/originated
Durability:  CAPACITY_BLOCKED
Execution:   new creation/admission/origin GATED
Allowed:     already restored media that requires no new durable state MAY continue
Forbidden:   evict T/A/P/fences, reuse identity, claim transient network failure
```

### CR-14 — AcceptedAuthorityStore bytes missing or non-canonical

```text
Injection:   C head digest exists but mandatory A bytes/signature are unavailable
             or no longer recompute that digest/context
Authority:   no reconstructed acceptance from digest-only data
Durability:  RESTORE_PENDING
Execution:   affected Conference GATED
Repair:      exact holder re-provision followed by full validation and durable write
```

### CR-15 — Independent Conference corruption

```text
Injection:   Conference A fails CR-10/11/14; Conference B has a complete valid store
Authority:   A blocked; B unchanged
Durability:  A RESTORE_PENDING; B COMMITTED/RESTORE_COMPLETE
Execution:   A GATED; B MAY become EXECUTABLE
Forbidden:   using B success to partially execute A
```

## Test execution requirements

Each storage implementation MUST execute every applicable case at least once
for a clean process kill and once for a simulated write/record fault where the
storage engine can represent it. Cases involving Native media MUST also assert
the external call count so “exactly once after durable authority” is observable.

```text
PASS
  every expected axis and forbidden behavior is demonstrated

FAIL
  any authority rollback/resurrection, pre-commit side effect, duplicate unwrap,
  silent evidence deletion or partial Conference execution occurs

INVALID_TEST
  injection point or durable/external evidence was not actually observed
```

An implementation-specific test plan MAY add cases but MUST NOT delete or
weaken CR-01 through CR-15.
