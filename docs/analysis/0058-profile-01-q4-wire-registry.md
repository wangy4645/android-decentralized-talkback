# ADR-0058 Profile 01 Q4 — V1 Wire Registry

## Status

```text
Registry:       FROZEN FOR Q4 V1
Golden vectors: SELF_CHECK_PASS (11 positive + 14 negative)
Parent:         0058-implementation-profile-01-control-crypto.md
Schema:         factSchemaVersion = 1
Implementation: NOT AUTHORIZED
```

This registry fixes the canonical binary shape used by Q3/Q4. Numeric keys and
enum values are protocol values. They MUST NOT be renumbered in schema V1.

Q7 added `MEDIA_KEY_PACKAGE.authority[13] = recipientKeyVersion` before any
0058 implementation authorization and before any schema-1 byte was emitted.
This registry and all golden vectors were replaced atomically, so the draft
schema remains version 1. After the first implementation authorization or
emitted schema-1 byte, any canonical-byte change requires a new schema version.

## Primitive types and bounds

```text
u8/u16/u32/u64     CBOR unsigned integer, value within named range
id128              CBOR byte string, exactly 16 bytes
digest256          CBOR byte string, exactly 32 bytes
moduleId           CBOR text, 1..32 ASCII bytes: [A-Za-z0-9._-]
reason             CBOR text or null; when text, 0..128 valid UTF-8 bytes
ssrc                u32
port                u16, 1..65535
signerKeyVersion    u32
```

No integer may be encoded as a negative value. Floats, CBOR `undefined`, tags,
indefinite-length items and duplicate map keys are forbidden. Required fields
MUST be present. A required nullable field is encoded as CBOR `null`; omission
is malformed and is not equivalent to null.

Set-like arrays MUST contain no duplicates and MUST already be in the order
specified below. A decoder MUST NOT sort an incoming array to repair it.

## Exact domain bytes

Every domain is the listed ASCII byte sequence followed by one NUL byte `00`:

```text
FACT_DIGEST_DOMAIN
  ASCII: TALKBACK-CONFERENCE-FACT-V1
  HEX:   54414c4b4241434b2d434f4e464552454e43452d464143542d563100

DESCRIPTOR_DIGEST_DOMAIN
  ASCII: TALKBACK-MEDIA-GROUP-DESCRIPTOR-V1
  HEX:   54414c4b4241434b2d4d454449412d47524f55502d44455343524950544f522d563100

SIGNATURE_DOMAIN
  ASCII: TALKBACK-CONFERENCE-FACT-SIGNATURE-V1
  HEX:   54414c4b4241434b2d434f4e464552454e43452d464143542d5349474e41545552452d563100

MEMBERSHIP_KEY_CONTEXT_DOMAIN
  ASCII: TALKBACK-MEMBERSHIP-KEY-CONTEXT-V1
  HEX:   54414c4b4241434b2d4d454d424552534849502d4b45592d434f4e544558542d563100

MEDIA_KEY_COMMITMENT_DOMAIN
  ASCII: TALKBACK-MEDIA-KEY-COMMITMENT-V1
  HEX:   54414c4b4241434b2d4d454449412d4b45592d434f4d4d49544d454e542d563100

SRTP_KEY_INFO_DOMAIN
  ASCII: TALKBACK-SRTP-KEY-INFO-V1
  HEX:   54414c4b4241434b2d535254502d4b45592d494e464f2d563100

PACKAGE_AAD_DOMAIN
  ASCII: TALKBACK-MEDIA-KEY-PACKAGE-AAD-V1
  HEX:   54414c4b4241434b2d4d454449412d4b45592d5041434b4147452d4141442d563100
```

## Top-level canonical objects

`FullFact` is a deterministic CBOR map:

```text
key 0  factSchemaVersion   u16; V1 = 1
key 1  factType            u16; closed enum below
key 2  authority           factType-specific map
key 3  signedMetadata      factType-specific map
```

`authorityDigestObjectBytes` is deterministic CBOR for this three-item array:

```text
[factSchemaVersion, factType, authority]
```

Exact digest calculation:

```text
factDigest = SHA-256(FACT_DIGEST_DOMAIN || authorityDigestObjectBytes)
```

`SignedFact` is a deterministic CBOR map:

```text
key 0  fullCanonicalBytes  byte string containing the complete FullFact bytes
key 1  signature           byte string, exactly 64 bytes low-S P-256 r||s
```

Exact signature calculation:

```text
signatureInput = SIGNATURE_DOMAIN || fullCanonicalBytes
signature      = ECDSA-P256-SHA256(signatureInput), normalized to low-S r||s
```

The existing JSON `SignalEnvelope` uses a distinct message type
`CONFERENCE_SIGNED_FACT`. Its payload is RFC 4648 standard base64 of the exact
`SignedFact` bytes with padding and without whitespace. Its existing
`signature` field remains the outer HMAC and never contains the ECDSA value.

Recommended decoder bounds for V1 are mandatory protocol limits:

```text
FullFact bytes           <= 4096
SignedFact bytes         <= 4608
Membership entries       <= 10
digest conflict set      <= 32 entries
Media Key ciphertext     1..512 bytes
```

## factType registry

```text
1   CREATION
2   MEMBERSHIP
3   TERMINATION
4   INVITE
5   INVITE_REVOKE
6   JOIN_REQUEST
7   JOIN_REJECTION
8   LEAVE_REQUEST
9   SOURCE_DECLARATION
10  SOURCE_RESOLUTION
11  MEDIA_KEY_PACKAGE
```

There is no V1 `REPLACEMENT` factType. Replacement is a new `CREATION` with a
non-null replacement binding.

## Shared nested structures

### MembershipEntry

Two-element CBOR array:

```text
index 0  moduleId                  moduleId
index 1  membershipIncarnationId  id128
```

`MembershipView` is an array of 1..10 entries sorted by ascending unsigned
lexicographic comparison of the canonical ASCII/UTF-8 `moduleId` bytes.

### MediaGroupDescriptor

CBOR map:

```text
key 0  addressFamily       u8; 4 = IPv4, 6 = IPv6
key 1  multicastAddress    byte string; 4 bytes for IPv4, 16 for IPv6
key 2  mediaPort           port
key 3  controlPort         port
key 4  underlayScope       u8; IP multicast hop-limit / TTL, 1..255
key 5  trafficProfile      TrafficProfile map
```

`TrafficProfile` map:

```text
key 0  codec               u8; 1 = OPUS_MONO
key 1  bitrateBps          u32; V1 = 24000
key 2  packetizationMs     u16; V1 = 20
key 3  packetsPerSecond    u16; V1 = 50
key 4  maxWirePacketBytes  u16; V1 = 120
key 5  dscp                u8; IP DSCP codepoint, V1 = 46 (EF)
```

`descriptorCanonicalBytes` is deterministic CBOR of the complete
`MediaGroupDescriptor` map:

```text
mediaGroupDescriptorDigest =
  SHA-256(DESCRIPTOR_DIGEST_DOMAIN || descriptorCanonicalBytes)
```

### ReplacementBinding

CBOR map, or CBOR `null` when the Creation is not a Replacement:

```text
key 0  replacesConferenceId       id128
key 1  replacesConferenceEpoch    u64
key 2  replacesCreationDigest     digest256
key 3  replacesTerminationDigest  digest256 or null
```

### MembershipKeyContextV1

This Q5 map normalizes Creation v0 and later Membership Facts into one key
context. It is not itself a Fact and is never accepted as Membership authority.

```text
key 0  conferenceId                    id128
key 1  conferenceEpoch                 u64
key 2  ownerModuleId                   moduleId
key 3  mediaGroupDescriptorDigest      digest256
key 4  membershipVersion               u64
key 5  previousMembershipDigest        digest256 or null
key 6  completeMembershipView          MembershipView
key 7  mediaKeyEpoch                   u64
key 8  cryptoProfileId                 u8; V1 = 1
```

Creation v0 uses CBOR `null` for key 5. Exact digest:

```text
membershipKeyContextDigest = SHA-256(
  MEMBERSHIP_KEY_CONTEXT_DOMAIN || canonical(MembershipKeyContextV1))
```

### PackagePlaintextV1

Deterministic CBOR map encrypted inside a Media Key Package:

```text
key 0  plaintextSchemaVersion          u16; V1 = 1
key 1  conferenceMediaSecret           byte string, exactly 32 bytes
key 2  membershipKeyContextDigest      digest256
```

### PackageAadCoreV1

Deterministic CBOR map copied from Media Key Package authority keys 0..10 plus
the Package authority key 13 recipient version:

```text
key 0  conferenceId                    id128
key 1  conferenceEpoch                 u64
key 2  ownerModuleId                   moduleId
key 3  membershipVersion               u64
key 4  mediaKeyEpoch                   u64
key 5  membershipFactDigest            digest256
key 6  mediaKeyCommitment              digest256
key 7  recipientModuleId               moduleId
key 8  packageIdentity                 id128
key 9  wrappedPEK                      byte string, exactly 384 bytes
key 10 gcmNonce                        byte string, exactly 12 bytes
key 11 recipientKeyVersion             u32
```

Exact AAD:

```text
packageAadBytes = PACKAGE_AAD_DOMAIN ||
  canonical([1, 11, PackageAadCoreV1])
```

Keys 11 `ciphertext` and 12 `gcmTag` are GCM outputs and therefore are not AAD
inputs. They remain authority fields in the final Media Key Package Fact.

Exact Q5 commitment and SRTP derivation:

```text
mediaKeyCommitment = HMAC-SHA-256(
  key  = conferenceMediaSecret,
  data = MEDIA_KEY_COMMITMENT_DOMAIN || membershipKeyContextDigest)

PRK = HMAC-SHA-256(
  key  = membershipKeyContextDigest,
  data = conferenceMediaSecret)

srtpMasterMaterial = HKDF-Expand-SHA256(
  PRK,
  SRTP_KEY_INFO_DOMAIN || membershipKeyContextDigest,
  28 bytes)

srtpMasterKey  = first 16 bytes
srtpMasterSalt = final 12 bytes
```

## Fact authority and signed-metadata registries

Every `signedMetadata` map contains key `0 signerKeyVersion`. Optional reasons
remain present and use CBOR `null` when absent.

### 1 CREATION

Expected signer: `ownerModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 ownerModuleId              moduleId
  3 mediaGroupDescriptor       MediaGroupDescriptor
  4 initialMembershipView      MembershipView
  5 initialMembershipVersion   u64; V1 origin = 0
  6 initialMediaKeyEpoch       u64; V1 origin >= 1
  7 mediaKeyCommitment         digest256
  8 keyDistributionProfile     u8; V1 = 1 (RSA3072_OAEP_AES256_GCM)
  9 replacementBinding         ReplacementBinding or null

signedMetadata
  0 signerKeyVersion           u32
  1 replacementReason          reason
```

### 2 MEMBERSHIP

Expected signer: `ownerModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 ownerModuleId              moduleId
  3 membershipVersion          u64
  4 previousMembershipDigest   digest256
  5 completeMembershipView     MembershipView
  6 mediaKeyEpoch              u64
  7 mediaKeyCommitment         digest256

signedMetadata
  0 signerKeyVersion           u32
  1 mutationReason             reason
```

### 3 TERMINATION

Expected signer: `ownerModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 ownerModuleId              moduleId
  3 terminatedCreationDigest   digest256
  4 terminalDisposition        u8; V1 = 1 (TERMINATED)

signedMetadata
  0 signerKeyVersion           u32
  1 terminationReason          reason
```

### 4 INVITE

Expected signer: `ownerModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 ownerModuleId              moduleId
  3 creationFactDigest         digest256
  4 inviteIdentity             id128
  5 targetModuleId             moduleId
  6 membershipBaselineDigest   digest256

signedMetadata
  0 signerKeyVersion           u32
```

### 5 INVITE_REVOKE

Expected signer: `ownerModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 ownerModuleId              moduleId
  3 creationFactDigest         digest256
  4 inviteIdentity             id128
  5 inviteFactDigest           digest256

signedMetadata
  0 signerKeyVersion           u32
  1 revokeReason               reason
```

### 6 JOIN_REQUEST

Expected signer: `requesterModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 requesterModuleId          moduleId
  3 creationFactDigest         digest256
  4 joinRequestIdentity        id128
  5 inviteIdentity             id128
  6 inviteFactDigest           digest256

signedMetadata
  0 signerKeyVersion           u32
```

### 7 JOIN_REJECTION

Expected signer: `ownerModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 ownerModuleId              moduleId
  3 creationFactDigest         digest256
  4 joinRequestIdentity        id128
  5 joinRequestDigest          digest256
  6 rejectionDisposition       u8; V1 = 1 (REJECTED)

signedMetadata
  0 signerKeyVersion           u32
  1 rejectionReason            reason
```

### 8 LEAVE_REQUEST

Expected signer: `requesterModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 requesterModuleId          moduleId
  3 creationFactDigest         digest256
  4 membershipIncarnationId    id128
  5 leaveRequestIdentity       id128
  6 observedMembershipDigest   digest256

signedMetadata
  0 signerKeyVersion           u32
```

### 9 SOURCE_DECLARATION

Expected signer: `senderModuleId`.

```text
authority
  0 conferenceId               id128
  1 conferenceEpoch            u64
  2 membershipVersion          u64
  3 mediaKeyEpoch              u64
  4 senderModuleId             moduleId
  5 sourceGeneration           u64
  6 sourceInstanceId           id128
  7 ssrc                       u32
  8 previousSourceDigest       digest256 or null
  9 mediaGroupDescriptorDigest digest256
 10 codecProfile               u8; V1 = 1 (OPUS_MONO)

signedMetadata
  0 signerKeyVersion           u32
```

### 10 SOURCE_RESOLUTION

Expected signer: `senderModuleId`.

```text
authority
  0 conferenceId                  id128
  1 conferenceEpoch               u64
  2 membershipVersion             u64
  3 mediaKeyEpoch                 u64
  4 senderModuleId                moduleId
  5 lastUncontestedSourceDigest   digest256 or null
  6 resolvesSourceConflictSet     array[digest256], 1..32
  7 newSourceGeneration           u64
  8 newSourceInstanceId           id128
  9 newSSRC                       u32
 10 mediaGroupDescriptorDigest    digest256

signedMetadata
  0 signerKeyVersion              u32
```

The conflict set is sorted by unsigned lexicographic raw digest bytes and
contains no duplicates.

### 11 MEDIA_KEY_PACKAGE

Expected signer: `ownerModuleId`.

```text
authority
  0  conferenceId               id128
  1  conferenceEpoch            u64
  2  ownerModuleId              moduleId
  3  membershipVersion          u64
  4  mediaKeyEpoch              u64
  5  membershipFactDigest       digest256; Creation digest for v0,
                                later Membership Fact digest otherwise
  6  mediaKeyCommitment         digest256
  7  recipientModuleId          moduleId
  8  packageIdentity            id128
  9  wrappedPEK                 byte string, exactly 384 bytes (RSA-3072)
 10  gcmNonce                   byte string, exactly 12 bytes
 11  ciphertext                 byte string, 1..512 bytes
  12  gcmTag                     byte string, exactly 16 bytes
 13  recipientKeyVersion        u32

signedMetadata
  0  signerKeyVersion           u32
```

The wrapped PEK, nonce, ciphertext and tag are authority. Any change under the
same `packageIdentity` changes `factDigest` and is a
`KEY_PACKAGE_IDENTITY_CONFLICT` candidate.

`recipientModuleId + recipientKeyVersion` identifies exactly one provisioned
Task Profile RSA establishment fingerprint. A recipient MUST NOT trial-decrypt
the Package with other retained private-key versions.

## Canonicality and verification

The verifier compares received FullFact bytes with a strict deterministic
re-encoding or rejects non-canonical forms during decoding. Re-encoding is only
a canonicality check. Signature verification always uses the originally
received bytes.

Unknown `factType`, unknown authority key, missing required key, extra
authority key, wrong primitive type, out-of-range value, unordered set,
duplicate set member, or non-null replacement of an explicit nullable field
fails closed.

The companion golden-vector JSON and its verifier are normative examples of
these byte rules. The test private key included with the generator is public,
test-only material and MUST NOT be copied into a Task Profile or product build.

Reproduce the artifact check from the repository root:

```text
python scripts/adr0058_q4_vectors.py \
  --verify docs/analysis/0058-profile-01-q4-golden-vectors.json
```

The checked artifact contains 11 PASS vectors (one for every closed V1
`factType`) and 14 fail-closed vectors. Its outer-HMAC/invalid-inner-signature
case includes and verifies a complete public test `SignalEnvelope` fixture.
