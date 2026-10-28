# ADR-0058 Implementation Profile 01 — Control / Crypto

## Status

```text
Profile:                FROZEN — CONTRACT COMPLETE
Grill:                  COMPLETE · Q1–Q10 PASS
LimitedAmend:           replay-window ownership → Profile 02 Q6
Parent:                 ADR-0058 ACCEPTED — CONTRACT ONLY
Underlay:               ADR-0059 ACCEPTED — PRODUCT RISK ASSUMPTION
Implementation:         AUTHORIZED (Profiles 01/02/03; E2b PENDING)
Conference multicast:   NOT IMPLEMENTED
ADR-0056:               RETAINED
```

This profile will map ADR-0058 Creation, Membership, Termination, Invite,
Request, Source, Resolution and Media Key Package semantics to concrete control
and cryptographic mechanisms. It must not change Q1–Q13 behavior.

## Current code baseline

```text
minSdk:                 26
current signaling:      JSON SignalEnvelope over UDP
current protection:     HMAC-SHA256 with one Shared Secret
current canonical form: delimiter-joined SignalEnvelope fields
per-Module keypair:     absent
Module trust store:     absent
per-recipient wrap:     absent
Conference media key:  not implemented
```

Current Shared Secret authentication proves only membership in the application
subnet. It does not prove that a control Fact was originated by the claimed
Module, because every member knows the same secret. It also cannot give a Media
Key Package recipient confidentiality from other group members.

## Q1 — Module identity root and trust binding

**ACCEPTED — 改 A**

V1 has two non-interchangeable authentication layers:

```text
Shared Secret + outer HMAC
  = application-subnet admission and cheap rejection of outsider datagrams
  ≠ Module identity
  ≠ Owner Fact origin proof
  ≠ per-recipient Package confidentiality

Module Identity
  = long-lived identity in the Mesh / application domain
  = Module signing key + Module key-establishment key + keyVersion
  ≠ per-Conference temporary identity
```

The trust root is a Task Profile binding:

```text
moduleId
signingPublicKey fingerprint
keyEstablishmentPublicKey fingerprint
keyVersion
```

The Task Profile binding MUST be installed before the Module can participate in
the ADR-0058 control plane. A Module absent from that binding MUST NOT be
automatically admitted by TOFU in V1.

HELLO remains a reachability / directory carrier. It MAY repeat a key identifier
for diagnostics, but it MUST NOT establish trust, replace a bound public key, or
silently advance `keyVersion`. Explicit enrollment, replacement and key rotation
are Q7 concerns.

Inner control facts MUST verify the claimed Module signature even when the outer
SignalEnvelope HMAC is valid. Outer HMAC success MUST NOT substitute for inner
Fact verification; inner Fact verification MUST NOT make an outsider datagram
bypass the application-subnet gate.

Owner-originated Creation, Membership and Termination Facts use the Owner's
Module signing key. Module-originated Request, Source and Resolution Facts use
that Module's signing key. Per-recipient Media Key Packages bind to the target
Module's key-establishment public key.

Unchanged V1 limitation:

```text
RTP/SRTP uses the Conference shared group media key.
Q1 does not add per-packet Module signatures.
Intra-group RTP impersonation remains the accepted CM-AUTH-3 limitation.
```

## Q2 — Cryptographic suites and Android key storage

**ACCEPTED — 改 A**

V1 uses exactly one cryptographic suite. The Conference control plane MUST NOT
negotiate algorithms through HELLO, a Fact, arrival order, or peer capability.
Changing an algorithm requires a new accepted revision of this Implementation
Profile. A mixed ECDH / RSA recipient-wrap deployment is not a V1 mode.

```text
Fact signature:              ECDSA P-256 / SHA256withECDSA
Fact and key fingerprint:    SHA-256
Recipient key establishment: RSA-3072-OAEP
OAEP message hash:           SHA-256
OAEP MGF:                    MGF1 with SHA-1
OAEP label:                  empty
Package Encryption Key:      256 random bits, unique per Package
Package content AEAD:        AES-256-GCM
GCM nonce:                   96 random bits, never reused with the same PEK
GCM authentication tag:      128 bits
Only KDF:                    HKDF-SHA-256
Outer subnet gate:           HMAC-SHA-256
Conference SRTP profile:     AEAD_AES_128_GCM
```

The fixed MGF1-SHA-1 parameter exists only for the V1 Android Keystore
RSA-OAEP compatibility path. It MUST NOT weaken or replace SHA-256 in Fact
signatures, fingerprints, commitments, or HKDF.

Each Media Key Package MUST use an independently generated 256-bit PEK. A PEK
MUST NOT be shared by two Package identities or recipients. AES-GCM nonce
uniqueness is scoped to its PEK; nonce reuse with the same PEK is forbidden.
Random keys, nonces and identity material MUST come from the platform secure
random generator and MUST NOT be derived from a Module id, Conference id,
counter alone, wall clock, or Shared Secret.

The Owner's ECDSA signature covers the complete canonical Media Key Package,
including all immutable context, recipient binding, wrapped PEK, nonce,
ciphertext and authentication tag. It does not merely sign the raw Conference
media key or its commitment. Q3 freezes canonical bytes and Q4 freezes the
signature envelope and verification order; neither may reduce this coverage.

If Package plaintext is used to derive the SRTP master material, HKDF-SHA-256
is the only permitted KDF. Its input and `info` MUST bind the complete Q7 key
context. Q5 freezes the exact inputs, output partition and recipient binding.
No implementation may substitute a bare hash, Shared Secret, Module id or
Conference id as an equivalent derivation.

Android API 26 and later use the same RSA recipient-wrap path. API 31+ devices
MUST NOT switch to Keystore ECDH merely because `PURPOSE_AGREE_KEY` is
available. This avoids two wire formats, two recipient-key meanings and two
key-lifecycle branches in V1.

Android key storage requirements:

```text
Module ECDSA signing private key
  MUST be generated and retained as a non-exportable Android Keystore key.

Module RSA key-establishment private key
  MUST be generated and retained as a non-exportable Android Keystore key.

Task Profile
  MUST bind moduleId, signing fingerprint,
  key-establishment fingerprint and keyVersion.

Conference media key / PEK plaintext
  MUST NOT be persisted in plaintext.
  MAY exist only in controlled process memory while required.
  MUST be retired from media execution on REMOVE, key retirement or TERMINATED.
```

The keys MUST be usable by the supported background and locked-screen product
states; V1 therefore MUST NOT require a per-use biometric or user-presence
prompt. Hardware-backed Keystore or StrongBox capability MAY be measured and
reported, but MUST NOT cause a silent identity replacement or a different V1
wire suite. Failure to load or use the bound private key fails closed; it MUST
NOT generate a replacement identity behind the same Task Profile binding.

The existing HMAC-SHA-256 remains only the outer application-subnet gate. It
MUST NOT derive a Module private key, PEK, Conference media key, Fact signature
or recipient wrap.

References:

- Android `KeyProperties` documents Keystore purposes and the API level of
  `PURPOSE_AGREE_KEY`: <https://developer.android.com/reference/android/security/keystore/KeyProperties>
- Android cryptography guidance and recommended algorithms:
  <https://developer.android.com/privacy-and-security/cryptography>
- Android Keystore storage model:
  <https://developer.android.com/privacy-and-security/keystore>

## Q3 — Canonical Fact encoding, digest and identity

**ACCEPTED — 改 A**

The outer `SignalEnvelope` MAY remain JSON. Every signed inner Conference Fact
MUST use canonical binary encoding. JSON, delimiter-joined strings, object key
insertion order and platform-native object serialization MUST NOT be used as
the canonical representation of a Fact.

V1 uses the RFC 8949 deterministic CBOR model with this strict subset:

```text
factSchemaVersion = 1
factType          = closed V1 enum

definite-length items only
shortest integer and length encoding only
duplicate map keys forbidden
floating-point values forbidden
CBOR undefined forbidden
text MUST be valid UTF-8
unknown authority fields fail closed
```

Q4 MUST freeze the concrete field numbers, `factType` values, CBOR shape and
golden vectors before implementation. A change that alters canonical authority
or full bytes MUST advance `factSchemaVersion`; a Profile revision that only
clarifies prose or preserves identical bytes MUST NOT advance it. V1 MUST NOT
negotiate schema through HELLO or an unsigned transport field.

Pre-implementation amendment boundary: Q7 added `recipientKeyVersion` before
any 0058 implementation authorization and before any schema-1 byte was emitted.
The draft registry and every golden vector were therefore replaced atomically
while `factSchemaVersion` remained 1. This exception closes at the first
implementation authorization or emitted schema-1 wire byte; every later
canonical-byte change MUST advance `factSchemaVersion`.

### Canonical blocks

Each Fact has three distinct concepts:

```text
identity
  = an explicit never-reused protocol identity where the ADR requires one

authorityCanonicalBytes
  = immutable fields that determine the Fact's executable authority

auditCanonicalBytes
  = signed metadata that does not change that authority
```

The signed full representation contains the recognized schema/type header,
authority block and audit block. The signature value itself is not recursively
included in those bytes. Q4 freezes the signature envelope.

```text
fullCanonicalBytes =
  canonical(schemaVersion, factType, authority block, audit block)

signatureInput =
  signature-domain || fullCanonicalBytes
```

The signature MUST cover the complete full canonical representation, including
audit metadata. Signing only a digest reference, raw media key, commitment or
selected fields is forbidden.

### Domain-separated digests

`factSchemaVersion` and `factType` are mandatory digest inputs, not transport
hints:

```text
factDigest = SHA-256(
  "TALKBACK-CONFERENCE-FACT-V1" ||
  factSchemaVersion ||
  factType ||
  authorityCanonicalBytes
)

mediaGroupDescriptorDigest = SHA-256(
  "TALKBACK-MEDIA-GROUP-DESCRIPTOR-V1" ||
  descriptorCanonicalBytes
)
```

The byte framing and exact domain constants are frozen by Q4 golden vectors;
they MUST be unambiguous and MUST NOT rely on delimiter-joined text. Fact and
Descriptor digest domains MUST remain distinct.

All `*Digest` values on the canonical wire are raw 32-byte SHA-256 values, not
hex strings. Digest chain references, commitments, tombstones and Invite /
Request bindings use the authority `factDigest`, never a hash that includes
audit-only metadata.

If `mediaKeyCommitment` is computed independently, Q5 MUST give it a distinct
domain separator and bind the complete canonical key context.

### Authority and audit boundaries

The V1 authority field sets are:

```text
Creation
  conferenceId, conferenceEpoch, ownerModuleId
  committed MediaGroupDescriptor and Traffic Profile
  initial complete MembershipView
  initial membershipVersion, initial mediaKeyEpoch
  mediaKeyCommitment and key-distribution descriptor

Membership
  conferenceId, conferenceEpoch, ownerModuleId
  membershipVersion, previousMembershipDigest
  completeMembershipView, mediaKeyEpoch, mediaKeyCommitment

Termination
  conferenceId, conferenceEpoch, ownerModuleId
  terminatedCreationDigest, terminalDisposition = TERMINATED

Invite / JoinRequest / LeaveRequest / Revoke / Reject
  the ADR-defined identity and binding digests
  authoritative Module, Conference epoch and incarnation fields
  authoritative disposition where that Fact has one

Source Declaration / Source Resolution
  the complete ADR Q5/Q6 Source identity and lineage authority
  previousSourceDigest or resolvesSourceConflictSet as applicable

Media Key Package
  conference and Membership/key context from ADR Q7
  membershipFactDigest, recipientModuleId, mediaKeyCommitment
  packageIdentity, wrapped PEK, GCM nonce, ciphertext and GCM tag
```

The Media Key Package cryptographic payload is executable authority, not audit
metadata. It MUST participate in `authorityCanonicalBytes` and therefore in
`factDigest`. The same `packageIdentity` with different ciphertext, wrapped
PEK, nonce, tag or other immutable authority produces a different digest and
is handled as `KEY_PACKAGE_IDENTITY_CONFLICT`.

Examples of audit-only metadata, where the parent ADR defines them as such:

```text
terminationReason
mutationReason
rejectionReason
replacementReason
```

An audit reason MUST NOT replace an authoritative disposition or binding. Audit
metadata is excluded from `factDigest` but included in `fullCanonicalBytes` and
therefore protected by the Module signature.

### Canonical identities, optionals and collections

The following protocol identities are never reassigned in V1, including across
process restart:

```text
inviteIdentity
joinRequestIdentity
leaveRequestIdentity
packageIdentity
sourceInstanceId
membershipIncarnationId
and every other ADR identity declared never reusable
```

An optional predecessor has exactly three distinguishable states:

```text
present predecessor  = raw 32-byte digest
explicit no predecessor = CBOR null
field missing        = malformed schema
```

CBOR `null`, not an all-zero digest, represents explicit none. It MUST NOT be
interpreted as a chain gap. A required but unavailable predecessor remains the
ADR's `CHAIN_PENDING` condition.

Every set-like collection has one canonical order and forbids duplicates:

```text
MembershipView entries
  = ascending unsigned lexicographic order of canonical moduleId UTF-8 bytes
  each entry contains moduleId + membershipIncarnationId

resolvesConflictSet / resolvesSourceConflictSet and digest sets
  = ascending unsigned lexicographic order of raw 32-byte digests
```

`moduleId` is a Task Profile identity, not user-facing display text. V1 Module
ids MUST already be canonical ASCII identifiers; locale-aware sorting,
case-folding and Unicode normalization at Fact construction time are forbidden.
Fact arrival order MUST NOT influence any encoded collection.

### Idempotence and conflicts

Authority idempotence and transport-byte idempotence are different:

```text
same fullCanonicalBytes
  → exact transport duplicate; fully idempotent

same authorityCanonicalBytes and factDigest, different audit block
  → same authoritative result
  → retain and report the applicable *_METADATA_CONFLICT
  → MUST NOT change, roll back or block that authoritative result

same identity, different authorityCanonicalBytes
  → different factDigest
  → INVALID / the ADR-defined authority or IDENTITY_CONFLICT

different identity, identical factDigest
  → cryptographic collision or implementation/integrity failure
  → fail closed; MUST NOT treat as idempotent
```

No receiver may reconstruct canonical bytes from a semantically normalized
object and then silently discard unknown authority. Verification operates on
the recognized V1 canonical schema; malformed, non-deterministic or
non-canonical encodings fail closed.

## Q4 — SignedFact envelope, verification order and wire artifacts

**ACCEPTED — 改 A · ARTIFACT SELF-CHECK PASS**

V1 carries two non-interchangeable protection layers:

```text
outer SignalEnvelope (existing JSON)
  protection = Shared Secret HMAC-SHA-256
  purpose    = application-subnet gate and cheap outsider rejection only
  payload    = one base64-encoded inner SignedFact envelope

inner SignedFact (canonical binary)
  fullCanonicalBytes = Q3 canonical CBOR Fact bytes
  signatureInput     = SIGNATURE_DOMAIN || fullCanonicalBytes
  signature          = fixed 64-byte ECDSA P-256 r || s
```

The inner ECDSA signature MUST have a separate field from the existing outer
`SignalEnvelope.signature` HMAC. No field may be interpreted as both an HMAC
and an ECDSA signature. The signature value is not recursively included in
`fullCanonicalBytes`.

### ECDSA wire form

```text
signature length = 64 bytes exactly
r = bytes [0, 32), unsigned big-endian, exactly 32 bytes
s = bytes [32, 64), unsigned big-endian, exactly 32 bytes

1 <= r < P-256 curve order
1 <= s <= P-256 curve order / 2       // low-S only
```

Android Keystore `SHA256withECDSA` signing returns DER on the supported path.
The sender MUST strictly parse that DER result, normalize `s` to low-S and emit
fixed-width `r || s` on the wire. A verifier MAY strictly wrap accepted raw
`r || s` as DER before calling the platform verifier. It MUST reject malformed,
out-of-range or high-S values before platform verification.

ECDSA may produce different valid signatures for the same input. Therefore:

```text
same fullCanonicalBytes + same signer/keyVersion
+ different signatures that all verify
  = signature variants of the same Fact
  != authority conflict
  != metadata conflict
```

The signature is not Fact identity. Q3 `factDigest` is the authority identity.
V1 defines no `SIGNATURE_METADATA_CONFLICT`.

### Signer and key-version binding

`signerKeyVersion` is signed non-authority metadata: it is present in the Q3
audit/non-authority block, participates in `fullCanonicalBytes`, and is excluded
from `factDigest`. It is an untrusted key selector until the signature verifies.

The expected signer is derived from the recognized `factType` authority:

```text
CREATION              -> ownerModuleId
MEMBERSHIP            -> ownerModuleId
TERMINATION           -> ownerModuleId
INVITE                -> ownerModuleId
INVITE_REVOKE         -> ownerModuleId
JOIN_REJECTION        -> ownerModuleId
MEDIA_KEY_PACKAGE     -> ownerModuleId

JOIN_REQUEST          -> requesterModuleId
LEAVE_REQUEST         -> requesterModuleId

SOURCE_DECLARATION    -> senderModuleId
SOURCE_RESOLUTION     -> senderModuleId
```

The Task Profile lookup MUST match the exact `(expected signer ModuleId,
signerKeyVersion)` and its bound signing-key fingerprint. Missing Module,
unknown key version, fingerprint mismatch or wrong signer fails closed.
Whether a new key version may re-sign old authority is a Q7 lifecycle decision;
Q4 does not authorize it merely because `factDigest` would remain unchanged.

`REPLACEMENT` is not a V1 `factType`. A formal Replacement is a new `CREATION`
whose authority contains the ADR-defined replacement binding. The replacement
reason remains audit metadata.

### Verification order

Each stage fails closed. Before stage 11, the candidate MUST NOT modify the Fact
store, FSM, staged key state, tombstone or Health projection.

```text
1.  verify outer SignalEnvelope HMAC
2.  enforce outer and inner payload size limits; recognize CONFERENCE_SIGNED_FACT
3.  parse the SignedFact envelope without trusting its contents
4.  strictly parse the received inner CBOR under the Q3 schema
5.  verify deterministic canonical equality
6.  derive the expected signer field from recognized factType authority
7.  look up signer + signerKeyVersion in the Task Profile
8.  validate fixed-64/range/low-S and verify ECDSA over received bytes
9.  compute the domain-separated authority factDigest
10. validate ADR semantics: epoch, Owner, identity, chain and commitment bindings
11. only now admit the result to Fact store / FSM / STAGED / tombstone / Health
```

Canonical equality MAY be checked by strict decoder rejection or by parsing and
deterministically re-encoding. If re-encoding is used, unequal bytes produce
`NON_CANONICAL_FACT`. ECDSA verification MUST always use the originally
received `fullCanonicalBytes`; it MUST NOT verify reconstructed bytes to repair
a non-canonical input.

Outer HMAC success does not validate an inner Fact. Inner Fact validity does not
grant Conference Membership, activate a key, admit a Source or admit media.

### Relay behavior

A holder re-provides the identical `fullCanonicalBytes + signature`. A relay
MUST NOT parse and re-encode the Fact, change audit metadata, re-sign it, or
replace the originator signature. Transport wrapping and base64 encoding MAY be
recreated only if they preserve the exact inner bytes.

### Required Q4 artifacts

Q4 is PASS only when both of these files exist and their self-check succeeds:

```text
0058-profile-01-q4-wire-registry.md
0058-profile-01-q4-golden-vectors.json
```

The registry MUST freeze the closed `factType` numeric table, every authority
and audit CBOR key, exact domain bytes, SignedFact envelope, explicit-null rule
and canonical collection rules. The vectors MUST cover every V1 Fact type and
include canonical bytes, authority digest, low-S signature, fixed test public
key and expected result.

Negative vectors MUST cover at least:

```text
valid outer HMAC with invalid inner signature
non-canonical CBOR
wrong signer Module
unknown signerKeyVersion
high-S signature
wrong signature length
modified audit
modified authority
unordered or duplicate set
modified Package ciphertext/tag
modified Package recipientKeyVersion
unknown factType
missing field substituted for explicit CBOR null
```

Golden signature bytes are fixed verification inputs generated with a public
test-only keypair. Production Keystore `sign()` is not required to reproduce
the same ECDSA nonce or signature bytes; it is required to produce a valid
low-S wire signature that verifies over the same canonical input.

Q4 artifacts are now present:

```text
docs/analysis/0058-profile-01-q4-wire-registry.md
docs/analysis/0058-profile-01-q4-golden-vectors.json
scripts/adr0058_q4_vectors.py

self-check: 11 positive + 14 negative vectors PASS
independent check: all 11 positive low-S signatures verify with cryptography
```

The Q4 vectors treat `mediaKeyCommitment` as an opaque authoritative 32-byte
field and prove Q4 encoding/signature behavior only. Q5 below freezes its
non-circular derivation; the separate frozen Q5 crypto vectors test that full
derivation independently without changing the Q4 wire vectors.

## Q5 — Membership key context, Package encryption and SRTP derivation

**ACCEPTED — 改 A**

Q5 removes the circular dependency in the parent ADR. A
`mediaKeyCommitment` MUST NOT include the final Membership/Creation
`factDigest`, because that Fact authority already contains the commitment.
The final Fact digest remains an external Package binding.

### Canonical MembershipKeyContext

V1 uses a dedicated normalized context rather than serializing a Membership
authority map with one field removed:

```text
MembershipKeyContextV1 {
  conferenceId
  conferenceEpoch
  ownerModuleId
  mediaGroupDescriptorDigest
  membershipVersion
  previousMembershipDigest
  completeMembershipView
  mediaKeyEpoch
  cryptoProfileId = 1
}

membershipKeyContextDigest = SHA-256(
  MEMBERSHIP_KEY_CONTEXT_DOMAIN ||
  canonical(MembershipKeyContextV1)
)
```

For the Creation initial Membership, `previousMembershipDigest` is explicit
CBOR `null`, and the other values come from the Creation authority. For a later
Membership Fact, it is that Fact's `previousMembershipDigest`, and the View,
version and key epoch come from that Membership authority. Both forms use the
committed Q3/Q4 `mediaGroupDescriptorDigest`.

The context's MembershipView uses the same sorted, duplicate-free canonical
entries as Q3/Q4. No implementation may hash a runtime collection, JSON object,
arrival order or locally repaired View as an equivalent context.

### Root secret and commitment

Each `mediaKeyEpoch` has one independently random 32-byte
`conferenceMediaSecret`. It is root secret material and MUST NOT be used
directly as an SRTP key, Package PEK, GCM nonce, Module key or outer HMAC key.

```text
mediaKeyCommitment = HMAC-SHA-256(
  key  = conferenceMediaSecret,
  data = MEDIA_KEY_COMMITMENT_DOMAIN || membershipKeyContextDigest
)
```

HMAC here is a commitment construction, not a second KDF. The commitment is
inserted into Creation/Membership authority and therefore participates in the
final Q3 `factDigest`.

### Non-circular origin order

```text
1. fix MembershipKeyContextV1
2. compute membershipKeyContextDigest
3. generate random 32-byte conferenceMediaSecret
4. compute mediaKeyCommitment
5. insert commitment into Creation/Membership authority
6. compute the final authority factDigest
7. construct one independently encrypted Package per recipient
8. originate the Fact; Package transport may occur before or after it
```

Changing any context authority, secret or commitment invalidates all Packages
for that candidate. Changing audit-only metadata does not change the context or
Fact digest. A Package MUST NOT be rebound to another candidate Fact.

For `MEDIA_KEY_PACKAGE` authority key 5, `membershipFactDigest` means the digest
of the authoritative Fact that carries the active Membership snapshot:

```text
Creation v0   -> Creation factDigest
later View    -> Membership factDigest
```

That digest binds a Package to the final Fact but MUST NOT be an input to
`mediaKeyCommitment`.

### Package plaintext and AAD

```text
PackagePlaintextV1 {
  plaintextSchemaVersion      = 1
  conferenceMediaSecret       32 bytes
  membershipKeyContextDigest  32 bytes
}
```

The Package AES-GCM associated data binds the complete pre-ciphertext header:

```text
PackageAadCoreV1 {
  conferenceId
  conferenceEpoch
  ownerModuleId
  membershipVersion
  mediaKeyEpoch
  membershipFactDigest
  mediaKeyCommitment
  recipientModuleId
  recipientKeyVersion
  packageIdentity
  wrappedPEK
  gcmNonce
}

packageAadBytes = PACKAGE_AAD_DOMAIN ||
  canonical([factSchemaVersion = 1,
             factType = MEDIA_KEY_PACKAGE,
             PackageAadCoreV1])
```

`ciphertext` and `gcmTag` are GCM outputs and are not AAD inputs. They remain
Q3/Q4 Package authority, participate in `factDigest`, and are covered by the
Owner signature.

Owner encryption is fixed:

```text
PEK          = 32 random bytes, unique per Package
wrappedPEK   = RSA-3072-OAEP-SHA256/MGF1-SHA1/empty-label(
                 TaskProfile[recipientModuleId, recipientKeyVersion]
                   .keyEstablishmentPublicKey,
                 PEK)
gcmNonce     = 12 random bytes, never reused with that PEK
ciphertext, tag = AES-256-GCM(
                    key = PEK,
                    nonce = gcmNonce,
                    plaintext = canonical(PackagePlaintextV1),
                    aad = packageAadBytes,
                    tagLength = 16)
```

The Owner then signs the complete Package under Q4. Recipient-public-key
encryption alone remains insufficient Owner authentication.

### Recipient verification and staging

V1 stages verified ciphertext, not decrypted secret, while the referenced Fact
is unavailable:

```text
1. verify Q4 Owner signature and Package schema
2. if matching accepted Membership authority Fact is unavailable:
     retain only as ENCRYPTED_STAGED; MUST NOT unwrap, decrypt or activate
3. require Package recipientKeyVersion == local activeKeyVersion and bind its
   Task Profile establishment fingerprint
4. require Package membershipFactDigest == accepted authority Fact digest
5. require Package commitment == accepted Fact commitment
6. reconstruct MembershipKeyContextV1 from the accepted facts
7. require its digest to match Package context after decryption
8. RSA unwrap PEK, then AES-GCM decrypt with exact packageAadBytes
9. recompute HMAC commitment from secret + context digest
10. only after all checks derive SRTP material and transition to ACTIVE
```

Failure at any stage MUST NOT overwrite an already valid ACTIVE key or roll
back Membership. Removed or locally terminated Modules MUST NOT unwrap or
activate a late Package.

### SRTP key derivation

V1 always derives, rather than directly using `conferenceMediaSecret`:

```text
PRK = HKDF-Extract-SHA256(
  salt = membershipKeyContextDigest,
  IKM  = conferenceMediaSecret)

srtpMasterMaterial = HKDF-Expand-SHA256(
  PRK  = PRK,
  info = SRTP_KEY_INFO_DOMAIN || membershipKeyContextDigest,
  L    = 28)

srtpMasterKey  = srtpMasterMaterial[0..16]    // 128 bits
srtpMasterSalt = srtpMasterMaterial[16..28]   // 96 bits
```

The output matches the master key and master salt sizes required by the Q2
`AEAD_AES_128_GCM` SRTP profile. A 16-byte-only output, a missing salt, direct
secret use, or derivation from Shared Secret / Module id / SSRC is forbidden.

### Failure taxonomy

```text
Fact digest, context digest, commitment, recipient, AAD,
unwrap, GCM tag or plaintext-schema mismatch
  -> INVALID_KEY_PACKAGE

competing Membership successors
  -> MEMBERSHIP_BRANCH_CONFLICT

two different secrets both verify the same accepted context + commitment
  -> CRYPTOGRAPHIC_KEY_CONFLICT
```

An invalid Package has no standing to create a key conflict. No arrival-order,
retry or higher-number rule may select one Package over another.

### Q5 crypto golden-vector gate

The following artifacts are normative implementation examples for Q5:

```text
docs/analysis/0058-profile-01-q5-crypto-vectors.json
scripts/adr0058_q5_crypto_vectors.py

self-check: 1 positive + 8 negative vectors PASS
```

They freeze and jointly verify the canonical Membership context, context
digest, HMAC commitment, Package plaintext, deterministic RSA-3072 OAEP fixture
with SHA-256/MGF1-SHA1/empty label, Package AAD, AES-256-GCM ciphertext/tag,
HKDF PRK and the 16-byte SRTP master key plus 12-byte master salt. Negative
vectors cover wrong OAEP MGF/label, recipient and key-version AAD, Membership
Fact digest AAD, GCM tag, commitment context and HKDF info.

The embedded RSA private key, PEK, media secret, nonce and OAEP seed are public
test fixtures and MUST NOT appear in product builds.

Q5 does not define replay policy (Q6), key lifecycle (Q7), or tombstone
persistence and retention (Q8).

## Q6 — Replay, nonce and context binding

**ACCEPTED — 改 A**

V1 has five replay defenses with different scopes. None substitutes for
another:

```text
L1  outer SignalEnvelope transport replay gate
L2  inner SignedFact authority idempotence and chain fencing
L3  Media Key Package identity and activation ledger
L4  persistent control-plane replay cursors across process restart
L5  SRTP packet-index / ROC replay protection
```

L1 is a short-lived application-subnet gate. Conference authority replay is
prevented by L2–L4; media replay is handled independently by L5.

### L1 — Outer transport gate

Every outbound `CONFERENCE_SIGNED_FACT` SignalEnvelope MUST use a fresh,
non-empty random nonce. V1 accepts either canonical UUIDv4 (122 random bits) or
16 random bytes encoded as canonical unpadded base64url. Timestamp, Module id,
counter, Fact digest and Shared Secret MUST NOT be used to derive the nonce.

Two transport-time concepts are distinct:

```text
maxTransportSkewMs
  = maximum accepted absolute difference between sender timestampMs
    and local wall clock
  = availability / transport admission policy only
  != Fact freshness or authority ordering

nonceRetentionMs
  = how long (from local receipt) a (fromModuleId, outerNonce) remains seen
  = measured with the local monotonic clock
  != sender timestampMs
```

V1 may initially configure both to the existing `15_000 ms` value, but the
implementation MUST keep them as separate meanings and settings. The nonce
cache MUST store local monotonic receipt time and MUST NOT use sender wall-clock
time for eviction.

After outer HMAC and timestamp-skew validation:

```text
unseen (fromModuleId, outerNonce)
  -> enter L2 and record nonce

seen pair within nonceRetentionMs
  -> TRANSPORT_DUPLICATE
  -> drop without writing or advancing Conference state
```

The L1 nonce cache MAY be memory-only and empty after process restart. A replay
that re-enters L2 after restart MUST remain harmless through L2–L4. Wall clock,
nonce arrival and transport acceptance MUST NOT order Facts.

Current-code note (not implementation authorization):
`TalkbackCoordinator.verifyIncomingSignal` presently combines a 15-second skew
check with an in-memory nonce table and stores sender `timestampMs` for nonce
eviction. That is L1 only. V1 implementation must split the two settings and
use local monotonic receipt time before claiming Q6 compliance.

### L2 — SignedFact authority replay

Inner Facts carry no additional replay nonce. Authority identity is the Q3
`factDigest` plus the ADR-defined identity and chain fields.

After Q4 signature validation and digest calculation:

```text
same authorityCanonicalBytes + same factDigest
+ same fullCanonicalBytes
  -> AUTHORITY_DUPLICATE
  -> no new authority record and no state transition

same authorityCanonicalBytes + same factDigest
+ different audit/full bytes
  -> same authoritative result with audit/signature variant
  -> retain applicable *_METADATA_CONFLICT evidence or signature variant
  -> MUST NOT block, duplicate, roll back or re-execute authority

different authorityCanonicalBytes + same factDigest
  -> HASH_COLLISION_OR_INTEGRITY_FAILURE
  -> fail closed

same protocol identity + different authority/factDigest
  -> the ADR-defined IDENTITY / BRANCH / authority conflict
```

An already retained `CHAIN_PENDING` Fact remains one pending candidate when
replayed. Duplicate arrival is not progress and MUST NOT create another pending
record, consume another retry budget or complete admission. Bounded diagnostic
or holder-availability metadata MAY be refreshed.

Chain classification remains semantic, not a generic replay label:

```text
already superseded / invalidated / fenced Fact  -> NON_RESURRECTION
valid future successor with missing predecessor -> CHAIN_PENDING
same parent with competing successors           -> BRANCH_CONFLICT
wrong Conference / epoch / recipient context    -> NOT_APPLICABLE / INVALID
```

### L3 — Media Key Package replay

`packageIdentity` is never reassigned anywhere in the V1 identity domain. It is
not reusable inside a new Conference, epoch, recipient or Package tuple.

Each accepted Package ledger entry binds:

```text
packageIdentity
packageFactDigest
conferenceId
conferenceEpoch
mediaKeyEpoch
membershipFactDigest
recipientModuleId
recipientKeyVersion
processingState
```

The tuple is immutable Package context and a storage index, not the identity
reuse scope.

```text
same packageIdentity + identical Package authority/binding
  -> PACKAGE_DUPLICATE
  -> preserve ENCRYPTED_STAGED / ACTIVE / RETIRED / REJECTED state
  -> MUST NOT unwrap, activate or retire twice

same packageIdentity + any different authority/binding
  -> KEY_PACKAGE_IDENTITY_CONFLICT
  -> MUST NOT select either by arrival order
```

Q5 PEK uniqueness and GCM nonce uniqueness remain cryptographic requirements;
they do not replace the Package identity ledger.

### L4 — Persistent control-plane replay cursors

Before Conference SignedFacts are processed after restart, V1 MUST atomically
restore enough accepted state to prevent authority rollback, key resurrection
and duplicate Package activation.

Minimum active-Conference replay state:

```text
accepted Creation factDigest
current Membership head factDigest
current membershipVersion
current mediaKeyEpoch
current mediaKeyCommitment
current membershipKeyContextDigest
retired mediaKeyEpoch high-water / fences

Package ledger entry:
  packageIdentity
  packageFactDigest
  recipientModuleId
  recipientKeyVersion
  membershipFactDigest
  mediaKeyEpoch
  processingState = ENCRYPTED_STAGED / ACTIVE / RETIRED / REJECTED

conflict / invalidation evidence still required for non-resurrection
```

Minimum terminated-Conference replay state:

```text
Conference Tombstone
accepted Termination factDigest
terminatedCreationDigest
```

The replay cursor does not replace the complete accepted Facts required for
holder re-provision, context reconstruction and audit. Q8 freezes logical
records, transaction boundary, retention and garbage-collection behavior;
the physical storage format remains an implementation decision.

After restart:

```text
empty outer nonce cache       != authority reset
old Fact                      MUST NOT replace current head
retired mediaKeyEpoch         MUST NOT become ACTIVE
processed packageIdentity     MUST NOT unwrap/activate again
missing required predecessor  -> CHAIN_PENDING / Fact request, not LWW
```

`RESTORE_PENDING` is a fail-closed local state: no Conference Fact, Package or
media key may become executable before replay state restoration completes.

### L5 — SRTP replay boundary

The SRTP runtime uses the Q5-derived master key/salt and the standard packet
index, ROC and replay-window mechanisms. Shared Secret, Conference id, RTP
timestamp, SSRC alone or Source Declaration alone MUST NOT substitute for SRTP
authentication and anti-replay.

```text
sender media-pipeline restart
  -> new sourceInstanceId + new SSRC under ADR-0058 Q5/Q11
  -> old ROC/window MUST NOT be rebound to the new Source

receiver pipeline rebuild without process restart
  -> preserve the admitted Source's ROC and replay state

old Conference epoch / retired mediaKeyEpoch packet
  -> reject through key fences and tombstone
  -> MUST NOT admit it because the SSRC appears unused
```

V1 chooses persistent replay protection for receiver process restart. For every
active admitted Source, the implementation MUST persist or conservatively
reserve ahead a replay fence bound to:

```text
conferenceId
conferenceEpoch
mediaKeyEpoch
senderModuleId
sourceInstanceId
SSRC
highest authenticated packet index / ROC replay fence
```

On restart the receiver MUST reject packet indices at or below the restored
conservative fence. A reservation-ahead scheme MAY deliberately drop a bounded
range of fresh packets after crash, but MUST NOT reopen already covered packet
indices.

L5 replay semantics and `replayWindowPackets` width are owned by Profile 02 Q6.
Media Runtime consumes only post-replay-admission packets. Reservation-ahead
block size, bitmap/checkpoint storage format and persistence cadence are
implementation details for preserving that Profile 02 fence; they MUST NOT
redefine the frozen width or admission semantics.

If the replay fence cannot be restored, that old Source MUST remain quarantined
until a verifiably new Source incarnation or new `mediaKeyEpoch` is accepted. It
MUST NOT start with an empty replay window under the old key + Source identity.

### Context mismatch is not duplication

Any mismatch in Conference id/epoch, Membership Fact digest,
`membershipKeyContextDigest`, recipient, Source incarnation or media key epoch
is `NOT_APPLICABLE` / `INVALID` under the relevant ADR rule. It MUST NOT be
silently counted as an idempotent duplicate.

Q6 does not freeze the Q8 persistent file/database format or Q7 key-version
re-signing policy. SRTP replay-window width is frozen by Profile 02 Q6;
checkpoint storage format remains an implementation detail of preserving that
fence.

## Q7 — Module key lifecycle, rotation, loss and re-enrollment

**ACCEPTED — 改 A**

Module identity remains a Task Profile trust binding. HELLO, Fact arrival,
Conference Membership, Shared Secret possession and media activity MUST NOT
enroll, rotate or restore a Module key.

### Version and Profile state

For each `moduleId`, `keyVersion` is a monotonically increasing unsigned
integer. It MUST NOT be reused or rolled back. V1 rotates the ECDSA signing and
RSA key-establishment keypairs together under one version.

```text
activeKeyVersion
  = the sole version permitted to originate new SignedFacts
  = the sole recipientKeyVersion permitted for new local Package activation

trustedVerifySet
  = versions whose signing public keys remain acceptable for inbound verify
  = derived from Task Profile key state, not from HELLO
  = exactly ACTIVE plus RETIRED_VERIFY
  = excludes PREPARED, RETIRED_OFF and REVOKED
```

Each Task Profile revision MUST bind at least:

```text
taskProfileRevision
moduleId
keyVersion
signingPublicKey fingerprint
keyEstablishmentPublicKey fingerprint
keyState
```

The Task Profile is authenticated by an independent operational trust root or
equivalent managed installation channel. A Module key being enrolled MUST NOT
self-authorize the Profile revision that trusts it. Each device MUST persist the
highest accepted `taskProfileRevision`; a lower or conflicting revision fails
closed and MUST NOT roll back active or trusted key state.

V1 key states are explicit:

```text
PREPARED
  public keys/fingerprints are provisioned for comparison only
  version is NOT in trustedVerifySet
  inbound Conference Facts under it fail NOT_YET_ACTIVE_SIGNER
  local candidate private keys MUST NOT originate or activate Packages

ACTIVE
  sole version for new origin and new recipient Package activation
  signing public key remains trusted

RETIRED_VERIFY
  private key MUST NOT originate or unwrap
  public signing key MAY verify historical/late Facts

RETIRED_OFF
  new inbound signatures under that version fail INVALID_SIGNER

REVOKED
  suspected compromise; verification and private-key use stop immediately
  no grace
```

V1 uses explicit Task Profile state transitions for verify grace. A local
wall-clock `verifyGraceMs` MUST NOT extend trust after the Profile says
`RETIRED_OFF` or `REVOKED`. A future implementation MAY use a local timer only
to retire earlier (fail closed), never to override the provisioned state.

### Two-phase re-enrollment

The only legal rotation/recovery path is explicit candidate enrollment followed
by activation:

```text
PREPARE
1. local Android Keystore generates non-exportable candidate N+1 signing and
   key-establishment keypairs
2. export only their public keys/fingerprints
3. operator creates a higher authenticated Task Profile revision with N+1
   state PREPARED (not yet trusted for Conference Fact verification)
4. deploy that revision to all relevant Modules

ACTIVATE
5. operator issues a still-higher authenticated Profile revision marking N+1
   ACTIVE and N RETIRED_VERIFY (or REVOKED for compromise)
6. deploy the activation revision; peers lacking it continue to reject N+1
7. local enrollment verifies candidate fingerprints match the ACTIVE Profile
8. atomically set activeKeyVersion = N+1 and enrollmentComplete = true
9. all new origin and recipient Package activation use N+1 only
10. N private keys become retired and MUST NOT originate or unwrap
```

The order MUST NOT be reversed: a Task Profile cannot bind fingerprints for
keys that have not yet been generated, and a generated candidate MUST NOT
become trusted merely because HELLO advertises it.

Provisioning a public key as PREPARED is not Conference trust. A receiver cannot
cryptographically distinguish an early unauthorized candidate signature from a
later authorized one unless an authenticated Profile revision changes the key
state. Therefore PREPARED MUST NOT validate a Conference SignedFact.

An unregistered Module or version remains `UNKNOWN_SIGNER` / `INVALID_SIGNER`.
V1 has no TOFU fallback. Recovery under the same `moduleId` always uses a new
`keyVersion` and new fingerprints; old fingerprints are never reassigned.

### Rotation Re-attestation

Q7 permits the original authority Module to attest already existing authority
under its new active signing key:

```text
authorityCanonicalBytes  exactly identical
factDigest                exactly identical
audit block               exactly identical except signerKeyVersion
fullCanonicalBytes        different because signerKeyVersion changed
signature                 newly produced by active key
```

This is a signature variant / Rotation Re-attestation, not a new authority
Fact. The signer role remains the Q4 authority field: Owner for Owner Facts,
requester for Requests, sender for Source Facts. A holder or relay MUST NOT
re-sign another Module's authority.

Before an old signing version becomes `RETIRED_OFF`, every live Fact,
Termination/Tombstone fact, or other authority that must remain newly
verifiable MUST either have a valid active-version Re-attestation or retain its
old version in `RETIRED_VERIFY`. Already accepted local authority remains
accepted when a verify key retires; retirement does not delete Fact-store state.

ECDSA randomness may still yield multiple valid signatures for the same full
bytes. Those remain Q4 signature variants, not conflicts.

### recipientKeyVersion binding and re-wrap

Every `MEDIA_KEY_PACKAGE` authority contains `recipientKeyVersion`. It is also
inside `PackageAadCoreV1`, the Package `factDigest`, and the Owner signature.

```text
recipientModuleId + recipientKeyVersion
  -> exact Task Profile RSA establishment fingerprint

recipient unwrap
  -> use only the private key for the declared version
  -> MUST NOT try every retained private key to guess the target
```

After local activation of N+1, a Package for N is not cryptographically invalid
but is locally unusable for activation:

```text
RECIPIENT_KEY_VERSION_RETIRED
```

For the same accepted Membership Fact, commitment, media secret and recipient,
the Owner MAY issue a new Package with a fresh `packageIdentity`, PEK, nonce and
`recipientKeyVersion = N+1`. That is an equivalent re-wrap and does not by
itself change Membership, `mediaKeyEpoch` or `conferenceMediaSecret`.

### Private-key unavailability

The two private-key capabilities fail independently:

```text
active signing private key unavailable
  -> MUST NOT originate any SignedFact

active key-establishment private key unavailable
  -> MUST NOT unwrap any Package

either capability requires recovery
  -> V1 re-enrolls both keypairs together as keyVersion + 1
```

If an already ACTIVE Conference media secret remains in controlled process
memory, media execution MAY continue until Membership removal, key retirement
or Termination. This does not restore control-origin or Package-unwrapping
capability. After process restart, the non-persisted plaintext media secret is
gone and MUST NOT be reconstructed without a valid Package.

An old Package that cannot be opened because its declared recipient version is
retired MUST NOT be relabeled `INVALID_KEY_PACKAGE`, moved to another version,
or used as proof that Membership failed. The Owner must re-wrap or rotate the
Conference key under the rules below.

### Planned rotation vs compromise

```text
planned rotation / normal Keystore migration
  -> does not automatically change mediaKeyEpoch
  -> equivalent re-wrap under N+1 is allowed

signing-key compromise
  -> old version REVOKED immediately
  -> old key gets no verify grace
  -> accepted authority remains fenced by L2/L4
  -> required live authority must be Re-attested under the newly enrolled key

establishment-key compromise or lost device
  -> captured Packages may reveal the current Conference media secret
  -> every affected active Conference MUST rotate conferenceMediaSecret
```

If the compromised/lost Module should leave, the Owner uses ADR REMOVE/REVOKE,
which already rotates the group key. If the Module remains after re-enrollment,
the Owner uses the Q7 `SECURITY_REKEY` Membership Mutation amendment:

```text
completeMembershipView may remain identical
membershipVersion MUST + 1
mediaKeyEpoch MUST + 1
conferenceMediaSecret, context digest and commitment MUST be new
new Packages target each retained member's active recipientKeyVersion
```

`SECURITY_REKEY` is authoritative key rotation, not an audit-only mutation and
not an arrival-order overwrite.

### Remote Profile changes and Membership separation

```text
remote Module originates N+1 before local Profile provisions it
  -> UNKNOWN_SIGNER / INVALID_SIGNER; no admission

Module removed from Task Profile
  -> reject every new signature and Package binding for that Module
  -> MUST NOT silently remove it from an existing Conference Membership View
```

Conference Membership changes remain Owner Facts under ADR-0058. Module
`keyVersion` rotation is not `mediaKeyEpoch` rotation except for the explicit
compromise-driven SECURITY_REKEY path.

### Persistence boundary

Q8 storage MUST atomically preserve at least:

```text
highest accepted taskProfileRevision
activeKeyVersion
all provisioned version fingerprints and keyState values
trustedVerifySet derived state
candidate/enrollment completion state
private-key aliases and purpose binding
```

During `RESTORE_PENDING`, no candidate key may become ACTIVE, no retired key may
originate/unwrap, and no half-applied Profile revision may affect verification.

Q7 does not freeze the Task Profile file format, operational distribution
transport, UI ceremony, or a default grace duration. It freezes the authenticated
monotonic revision, explicit states, two-phase ordering and fail-closed behavior.

## Q8 — Persistent authority, tombstones and restart recovery

**ACCEPTED — 改 A**

Persistence is part of the Conference authority fence. A process restart,
storage compaction or media-runtime rebuild MUST NOT restore an older
Membership, a retired key, a terminated Conference, or an already processed
Package identity.

### Logical persistence domains

The physical database and file layout are deferred. V1 MUST preserve these
logical domains independently enough that one damaged Conference can fail
closed without making unrelated Conferences executable from partial state:

```text
G  GlobalTrustStore
     authenticated Task Profile revisions and Q7 key-version state

C  ConferenceReplayState
     active Conference authority heads, fences and conflict evidence

T  ConferenceTombstone
     irreversible local knowledge of accepted Termination

P  PackageLedger
     every locally observed packageIdentity and immutable Package binding

A  AcceptedAuthorityStore
     mandatory exact received canonical bytes + signature needed to
     reconstruct accepted authority and satisfy holder re-provision

V  RedundantVariantCache
     optional redundant signature/audit variants; never required for authority

M  MediaReplayCheckpoint
     SRTP replay fence whose width/admission semantics are owned by
     Profile 02 Q6; storage format is implementation detail
```

Plaintext `conferenceMediaSecret`, PEK, derived SRTP master key/salt and any
recoverable plaintext ACTIVE media material MUST NOT be persisted. Encrypted
Media Key Package bytes MAY and, for restart recovery, MUST be retained in
`A`/`P` for the selected active Package.

### Durable state before emission or execution

A local database transaction cannot atomically commit Android Keystore state,
network emission and Native Media Runtime side effects. V1 therefore uses a
strict write-before-emit/execute boundary.

For an inbound authority advance:

```text
validate complete received bytes
  -> atomically commit the accepted Fact/Package plus all affected C/P/A fences
  -> only after durable commit expose the result to FSM, key activation,
     group binding, Source admission or media execution
```

For a locally originated Fact or Package:

```text
construct canonical bytes and signature
  -> atomically persist the exact authored bytes, identity and authority advance
  -> only after durable commit emit a SignalEnvelope
```

After a crash, origin retries MUST relay the already committed bytes. They MUST
NOT regenerate different authority or audit bytes under the same protocol
identity.

One authority transaction MUST include every durable item affected by that
advance, including as applicable:

```text
accepted or authored SignedFact / Package bytes
ConferenceReplayState head, version, key epoch and non-resurrection fences
PackageLedger state
Source / Membership / key admission fences
conflict evidence required to prevent silent LWW
```

Commit failure means the local authority advance was not accepted or
originated. External side effects MUST NOT run before commit. If a crash occurs
after commit but before an external side effect, restart reconciliation executes
the still-pending effect from durable state; it does not roll the authority
back.

Termination is the same shape:

```text
atomically persist exact Termination SignedFact in A
+ install T
+ mark/remove the active C view as TERMINATED
+ retire Package/key/Source execution fences
+ record pending media cleanup
  -> commit
  -> stop send, leave group and clear in-memory key material
```

A tombstone commit MUST NOT leave a durable staged or ACTIVE Package executable.

### Active ConferenceReplayState

At minimum `C` persists:

```text
conferenceId, conferenceEpoch
creationFactDigest
membershipHeadFactDigest, membershipVersion, mediaKeyEpoch
mediaKeyCommitment, membershipKeyContextDigest
retiredMediaKeyEpochHighWater and explicit exceptional fences
local membershipIncarnationId and sticky LEFT/REMOVED participation state
CHAIN_PENDING / BRANCH_CONFLICT / Resolution candidate digests still needed
processingState = ACTIVE | RESTORE_PENDING
```

`A` MUST contain the exact authority bytes needed to recompute every current
digest/context and every accepted Fact for which this Module still has an ADR
holder re-provision obligation. A digest-only row is not sufficient.

### Package ledger and recovery unseal

`P` binds every locally observed identity as frozen by Q6:

```text
packageIdentity -> {
  packageFactDigest,
  conferenceId, conferenceEpoch,
  membershipFactDigest, mediaKeyEpoch,
  recipientModuleId, recipientKeyVersion,
  processingState = ENCRYPTED_STAGED | ACTIVE | RETIRED | REJECTED
}
```

Originators never reassign a `packageIdentity` in the V1 Identity Domain.
Receivers enforce uniqueness over their complete persisted observation domain;
they do not claim to prove that an unseen remote history never reused an id.

Q6's prohibition on duplicate Package processing does not prohibit the narrow
restart operation `RECOVERY_UNSEAL`:

```text
RECOVERY_UNSEAL
  input = exact retained encrypted bytes of the Package already recorded ACTIVE
  target = the current accepted Membership head and mediaKeyEpoch only
  result = rebuild controlled in-process media material

  != accept Package again
  != advance P or C
  != create a second activation
  != try multiple retained recipient private keys
```

Recovery uses only the Package-declared `recipientKeyVersion`, which must still
be the locally ACTIVE Q7 establishment version and match the Task Profile
fingerprint. If the declared private key is unavailable or retired, or any
Fact/commitment/context check fails, recovery remains `KEY_PENDING`. The Owner
must issue a valid equivalent re-wrap or authoritative key rotation. Old keys
MUST NOT be trial-unwrapped.

### Startup restoration

Process start places every persisted Conference control plane in
`RESTORE_PENDING`. Recovery order is:

```text
1. restore G and reconcile Task Profile state with Keystore aliases/purposes
2. restore C / T / P / A independently per Conference
3. restore M, or mark the relevant Source SRTP_FENCE_UNKNOWN
4. cross-check all authority, identity, commitment and fence bindings
5. reconcile committed-but-not-executed cleanup/activation intents
6. mark that Conference RESTORE_COMPLETE, or fail it closed
```

No Fact, Package, key, Source, group binding or media execution becomes usable
for that Conference before its restoration completes. One Conference failure
MUST NOT authorize partial recovery of that Conference; other independently
valid Conferences MAY complete.

Minimum cross-checks include:

```text
C Creation/head digests recompute from mandatory A bytes
C membership core recomputes membershipKeyContextDigest and commitment context
P ACTIVE points to current C head/keyEpoch and this recipient/version
T and an executable active C view cannot coexist
T creation/termination bindings match exact A Termination authority
each observed packageIdentity has one immutable binding
G activeKeyVersion matches provisioned fingerprints, state and Keystore aliases
M either restores a conservative replay fence or quarantines that Source
```

An orphaned Keystore candidate may be retained as non-authoritative PREPARED
material or deleted by an explicit enrollment cleanup. It MUST NOT become
ACTIVE merely because its alias exists. Any mismatch that could reopen old
authority, keys or media fails that Conference closed; repair may fetch exact
Facts from holders but MUST NOT synthesize missing authority.

### Tombstone content and retention

An accepted Termination atomically creates an irreversible local Tombstone:

```text
conferenceId, conferenceEpoch, creationFactDigest
terminationAuthorityDigest
exact Termination SignedFact bytes/signature, or a mandatory durable A reference
retired MediaGroupEndpoint
retired/burned Source discriminator evidence required by the ADR
```

Diagnostic version fields are not LWW authority. A Tombstone rejects every
operational Fact and old media bound to the terminated Creation. It MUST survive
process restart, application upgrade and ordinary storage maintenance. It MUST
NOT contain recoverable plaintext key material or imply that native media
resources remain allocated.

V1 does not delete Tombstones or their required identity fences by TTL. They
may be removed only by an explicit contract that retires the entire applicable
Identity Domain. A compressed retired/burned set used for a security decision
MUST have no false negatives; a diagnostic hash alone is not a fence.

`P` entries required to reject identity reuse remain archived with `T` after
Termination. Termination of a Conference does not make a Package identity
reusable.

### Capacity exhaustion

Finite storage MUST fail closed instead of silently evicting authority:

```text
PERSISTENCE_CAPACITY_EXHAUSTED
  -> do not evict T, required A bytes, P identities or non-resurrection fences
  -> reject creation/admission/origin that requires additional durable state
  -> already restored media requiring no authority write MAY continue
  -> expose an operational fault requiring explicit domain retirement,
     archive/export policy or provisioned capacity remediation
```

Disk quota, SQLite/WAL choice, file paths and compaction algorithm are deferred,
but silent eviction is not an implementation option.

### Q8 boundary

Q6 defines which replay facts must survive; Q8 freezes durable ordering,
mandatory authority bytes, recovery unseal, Tombstone retention and restart
behavior. Profile 02 Q6 freezes SRTP replay-window width and admission
semantics; checkpoint storage format remains an implementation detail of
preserving that fence. Conference Health is recomputed after
restore and is not itself authority that must be persisted.

The frozen Q8 acceptance artifact is:

```text
docs/analysis/0058-profile-01-q8-crash-recovery-matrix.md
```

It defines CR-01 through CR-15. A future physical storage implementation MUST
run the applicable injections; the frozen matrix does not select SQLite, WAL or
a file format and is not evidence that such implementation tests already pass.

## Q9 — Failure taxonomy and fail-closed projection

**ACCEPTED — A**

Failure is not one scalar `ERROR`, `RECOVERING` or `ONLINE` state. V1 records
authority meaning, durability and execution eligibility as separate axes. A
result on one axis MUST NOT manufacture progress on another.

### Three orthogonal result axes

```text
Authority Verdict
  ACCEPTED
  IDEMPOTENT
  PREREQUISITE_PENDING
  CONFLICT
  INVALID
  NOT_APPLICABLE

Durability State
  NOT_COMMITTED
  COMMITTED
  RESTORE_PENDING
  CAPACITY_BLOCKED

Execution State
  EXECUTABLE
  GATED
  QUARANTINED
  TERMINATED
```

The Authority Verdict applies only after the validation stage needed to make
that verdict. A transport packet rejected by outer HMAC, bounds or rate gates
does not create Conference authority evidence. A signer that cannot yet be
validated against `G` does not create an accepted or pending authority Fact.

`COMMITTED` means the Q8 transaction is durable; it does not mean the object is
executable. A committed conflict remains quarantined, and a committed
Membership with no active matching key remains gated.

### Required classification

```text
same accepted Fact/Package authority and identity
  -> IDEMPOTENT
  -> preserve prior durable/execution state
  -> do not repeat origin, unwrap, activation or media side effects

valid successor with a specifically named missing predecessor
  -> PREREQUISITE_PENDING / GATED
  -> retain the verified candidate and missing-digest evidence

CHAIN_PENDING, SOURCE_CHAIN_PENDING or matching Fact-before-key/key-before-Fact
  -> PREREQUISITE_PENDING under that domain's frozen rules
  -> not generic retry and not authority rollback

BRANCH, identity, Resolution or cryptographic-key conflict
  -> CONFLICT
  -> persist the verified conflict evidence required for resolution
  -> quarantine only the scope defined by ADR-0058

wrong Conference/epoch/recipient, already fenced generation,
or operational Fact under an accepted Tombstone
  -> NOT_APPLICABLE or the ADR's explicit non-resurrection result
  -> no current-state mutation

bad signature, bad commitment proof, malformed/non-canonical inner Fact,
REVOKED/RETIRED_OFF signer, identity mutation or integrity failure
  -> INVALID
  -> no authority execution

RESTORE_PENDING
  -> durability RESTORE_PENDING + execution GATED

PERSISTENCE_CAPACITY_EXHAUSTED
  -> durability CAPACITY_BLOCKED
  -> block every operation requiring new durable authority
```

Source conflicts normally quarantine only the affected Source/incarnations and
project `DEGRADED` under ADR Q12. Creation, Membership, current cryptographic
key conflict and `COLLISION_BLOCKED` retain their already frozen Conference-wide
blocking projection. Q9 MUST NOT broaden a local Source conflict into a global
Conference conflict.

### Trust not active is not accepted authority

Q7 trust states retain exact meanings:

```text
signer key state PREPARED
  -> TRUST_NOT_ACTIVE
  -> discard or boundedly diagnose the unaccepted input
  -> MUST NOT persist it as accepted authority or conflict evidence

signer/version absent from the authenticated local Task Profile
  -> UNKNOWN_SIGNER / INVALID_SIGNER
  -> no TOFU, HELLO enrollment or automatic authority retry

signer RETIRED_OFF or REVOKED
  -> INVALID_SIGNER
  -> same version cannot become valid through timer/retry
```

After an authenticated monotonic Task Profile revision changes a PREPARED key
to ACTIVE, the sender/holder may provide the exact Fact again and normal Q4
validation starts from the beginning. That is a new prerequisite state, not a
retry that converted an earlier authority verdict. Unknown/unverified bytes
MUST NOT be kept in the mandatory authority store merely in hope of later
trust.

### Recovery actions have a named trigger and owner

Every non-terminal recovery attempt MUST bind to one of these actions:

```text
FETCH_FACT
  trigger: a verified, explicitly named chain/digest prerequisite is missing
  actor: any permitted requester; holder may re-provide exact bytes

OWNER_ACTION
  trigger: Membership, key Package, SECURITY_REKEY or Termination authority
  actor: current Conference Owner only

SENDER_ACTION
  trigger: Source lineage/Resolution owned by that sender
  actor: the corresponding sender only

OPERATOR_ACTION
  trigger: Task Profile, Keystore enrollment, persistence capacity or corruption
  actor: authenticated operational process/person

NEW_EVENT_ONLY
  trigger: a new user intent, newly accepted Fact or otherwise frozen event
  actor: the domain-defined originator

NONE
  applies to permanent INVALID, TERMINATED and already completed idempotence
```

An implementation MUST NOT label `waitingForPrimary`, timer expiry, generic
network retry, UI activity or audible media as a recovery action. `FETCH_FACT`
must name the digest/identity it is missing and be bounded operationally; its
repetition is not progress.

`PREREQUISITE_PENDING` MAY be re-evaluated only when its named prerequisite or
another explicitly allowed event changes. A permanently invalid cryptographic
or canonical input MUST NOT be made valid by time, arrival order or retry.

### Evidence retention and denial-of-service boundary

Verified conflict and chain evidence required by Q6/Q8 non-resurrection MUST be
durable. Unauthenticated, malformed, unknown-signer and other pre-verdict input
MUST be bounded/rate-limited diagnostic data and MUST NOT grow `A`, `P`, `C` or
`T` without limit.

Invalid diagnostics and transport counters are observational. They MUST NOT:

```text
originate a Fact
advance Membership/key/source state
activate a staged key
create a Tombstone
admit media
consume identity as if accepted authority
```

### Health is downstream only

Q9 results feed the ADR Q12 orthogonal Health projection. Health and UI never
feed back into Q9 authority or durability. Audible media cannot hide a blocking
control conflict; silence cannot manufacture a transport or authority failure;
`ONLINE`, `READY`, `DEGRADED` and `RECOVERING` are not acceptance evidence.

## Q10 — Sufficiency and implementation-authorization boundary

**ACCEPTED — 改 A**

Profile 01 is sufficient and frozen because all of the following now exist and
agree:

```text
Q1–Q9 mechanism decisions
Q4 V1 wire registry
Q4 11-positive / 14-negative canonical/signature vectors
Q5 1-positive / 8-negative end-to-end crypto vectors
Q8 CR-01..CR-15 crash/recovery acceptance matrix
ADR-0058 narrow clarifications for non-circular commitment,
recipientKeyVersion and SECURITY_REKEY
```

This verdict changes only Profile 01:

```text
Profile 01:       FROZEN — CONTRACT COMPLETE
Profile 02:       FROZEN — CONTRACT COMPLETE / CLOSED
Profile 03:       FROZEN — CONTRACT COMPLETE / CLOSED
Implementation:  AUTHORIZED (post-readiness; E2b PENDING)
ADR-0056:         RETAINED
```

Authorization recorded in `0058-implementation-authorization.md`.

## Decisions to freeze

```text
Q1  Module identity root and trust binding
Q2  Exact signature / key-establishment / AEAD suites and Android storage
Q3  Canonical Fact encoding, digest and identity
Q4  Fact signature envelope and verification order
Q5  Media Key Package derivation, wrap and recipient binding
Q6  Replay, nonce and context binding
Q7  Key lifecycle, rotation, loss and Module re-enrollment
Q8  Fact / tombstone retention and restart behavior
Q9  Failure taxonomy and fail-closed projection
Q10 Sufficiency and implementation authorization boundary
```

Only one question is decided at a time. No mechanism in this profile may turn
the Shared Secret, arrival order, wall clock or transport reachability into
Conference authority.
